#!/usr/bin/env python3
"""장애 주입 버튼 패드 — 정적 페이지 서빙 + actions.sh 실행 + Prometheus 프록시.

앱 밖, 호스트 쪽에 둔다. 사고 대부분이 앱이 자기 자신에게 할 수 없는 일이고
(SIGKILL, env 를 바꿔 재기동, Redis 정지, SQL 훼손), 앱이 죽어 있는 동안에도
패드는 살아 있어야 하기 때문이다.

파이썬은 HTTP 서빙·프로세스 관리·프록시만 한다. docker/mysql/curl 조작은 전부
actions.sh 가 scenarios/lib.sh 를 source 해서 한다 — 스크립트와 버튼이 한 소스여야 한다.

    python3 server.py [--port 8090]
"""

import argparse
import json
import os
import re
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAD_DIR = os.path.dirname(os.path.abspath(__file__))
ACTIONS = os.path.join(PAD_DIR, "actions.sh")
CATALOG = os.path.join(PAD_DIR, "catalog.json")
INDEX = os.path.join(PAD_DIR, "index.html")
PROM = os.environ.get("PROM", "http://localhost:9090")

# 로그 창에 흘릴 때 색 코드를 지운다. lib.sh 의 say() 가 굵게 찍는다.
ANSI = re.compile(r"\x1b\[[0-9;]*m")
ENV_VALUE = re.compile(r"^[A-Za-z0-9._-]{1,32}$")


def load_catalog():
    with open(CATALOG, encoding="utf-8") as f:
        return json.load(f)


class Allowlist:
    """catalog.json 에서 뽑아낸 허용 목록.

    브라우저가 보낸 문자열이 셸에 닿는 경로가 없어야 한다. 액션 이름은 이 목록에
    있어야 하고, 인자는 선언된 파라미터(정수)와 선언된 env 조합뿐이다.
    """

    def __init__(self, catalog):
        self.params = {}      # action -> [{name, type, default, min, max}]
        self.env_sets = {}    # action -> {frozenset(env.items()), ...}
        self.env_keys = set(catalog["env_defaults"])
        for card in catalog["cards"]:
            for btn in card.get("buttons", []):
                action = btn["action"]
                self.params.setdefault(action, btn.get("params", []))
                if "env" in btn:
                    self.env_sets.setdefault(action, set()).add(
                        frozenset(btn["env"].items())
                    )

    def build_argv(self, action, args, current_env):
        """(argv, env_after) 를 만든다. 어긋나면 ValueError."""
        if action not in self.params:
            raise ValueError(f"허용 목록에 없는 액션: {action}")

        argv = [ACTIONS, action]
        env_after = dict(current_env)

        for spec in self.params[action]:
            raw = args.get(spec["name"], spec.get("default"))
            if isinstance(raw, bool) or not isinstance(raw, (int, str)):
                raise ValueError(f"{spec['name']} 는 정수여야 한다")
            try:
                value = int(raw)
            except (TypeError, ValueError):
                raise ValueError(f"{spec['name']} 는 정수여야 한다")
            lo, hi = spec.get("min", 0), spec.get("max", 10 ** 9)
            if not lo <= value <= hi:
                raise ValueError(f"{spec['name']} 는 {lo}~{hi} 범위여야 한다")
            argv.append(str(value))

        if action in self.env_sets:
            env = args.get("env") or {}
            if not isinstance(env, dict):
                raise ValueError("env 는 객체여야 한다")
            if frozenset(env.items()) not in self.env_sets[action]:
                raise ValueError("catalog 에 선언되지 않은 env 조합이다")
            for k, v in env.items():
                if k not in self.env_keys or not ENV_VALUE.match(str(v)):
                    raise ValueError(f"허용되지 않은 env: {k}={v}")
                env_after[k] = str(v)
            # restart_app_with 는 넘긴 env 만 export 한다. 워커 정지와 스텁 지연을
            # 동시에 걸려면 매번 집합 전체를 넘겨야 한다.
            argv += [f"{k}={v}" for k, v in sorted(env_after.items())]

        return argv, env_after


class Runner:
    """actions.sh 를 백그라운드로 하나씩만 돌리고, 출력을 줄 단위로 쌓는다."""

    def __init__(self, allowlist, env_defaults):
        self.allow = allowlist
        self.env_defaults = dict(env_defaults)
        self.env = dict(env_defaults)
        self.lock = threading.Lock()
        self.lines = []
        self.running = None      # 실행 중 액션 이름
        self.exit_code = None
        self.proc = None

    def _append(self, text):
        with self.lock:
            self.lines.append(ANSI.sub("", text.rstrip("\n")))

    def start(self, action, args):
        with self.lock:
            if self.running is not None:
                return None, f"이미 실행 중이다: {self.running}"
        try:
            argv, env_after = self.allow.build_argv(action, args, self.env)
        except ValueError as exc:
            return None, str(exc)

        with self.lock:
            if self.running is not None:
                return None, f"이미 실행 중이다: {self.running}"
            self.running = action
            self.exit_code = None
            self.env = env_after
        self._append(f"── {action} 시작 ──")
        threading.Thread(target=self._run, args=(argv, action), daemon=True).start()
        return action, None

    def _run(self, argv, action):
        code = -1
        try:
            self.proc = subprocess.Popen(
                argv,
                cwd=PAD_DIR,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
            )
            for line in self.proc.stdout:
                self._append(line)
            code = self.proc.wait()
        except Exception as exc:                    # 실행 자체가 안 된 경우
            self._append(f"실행 실패: {exc}")
        finally:
            self.proc = None
            self._append(f"── {action} 종료 (exit {code}) ──")
            with self.lock:
                self.running = None
                self.exit_code = code
                # 스택을 새로 올리거나 내리면 컨테이너는 compose 기본값으로 돌아간다.
                # 서버가 기억하던 env 를 같이 되돌리지 않으면 상태 띠가 거짓 drift 를 표시한다.
                if action in ("stack_up", "stack_down"):
                    self.env = dict(self.env_defaults)

    def log(self, since):
        with self.lock:
            since = max(0, min(since, len(self.lines)))
            return {
                "next": len(self.lines),
                "lines": self.lines[since:],
                "running": self.running,
                "exit_code": self.exit_code,
            }

    def snapshot_env(self):
        with self.lock:
            return dict(self.env), self.running


_STATE_LOCK = threading.Lock()


def read_state():
    """actions.sh state 를 동기로 한 번 부른다. 실패해도 JSON 을 돌려준다.

    docker exec 를 예닐곱 번 부르므로 몇 초 걸릴 수 있다. 브라우저 폴링이 겹쳐도
    프로세스가 쌓이지 않도록 한 번에 하나만 돈다 — 뒤에 온 요청은 기다렸다가 자기 것을 받는다.
    """
    with _STATE_LOCK:
        return _read_state_once()


def _read_state_once():
    try:
        out = subprocess.run(
            [ACTIONS, "state"], cwd=PAD_DIR, capture_output=True, text=True, timeout=25
        )
        for line in reversed(out.stdout.strip().splitlines()):
            line = line.strip()
            if line.startswith("{"):
                return json.loads(line)
        return {"error": "state 가 JSON 을 내지 않았다", "stderr": out.stderr[-400:]}
    except Exception as exc:
        return {"error": f"state 실패: {exc}"}


def prom_get(path, params=None):
    url = f"{PROM}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=6) as resp:
        return json.load(resp)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "faultpad"

    # 액세스 로그는 끈다. 로그 창이 봐야 할 것은 사고 실행 로그뿐이다.
    def log_message(self, fmt, *args):
        pass

    # ── 응답 도우미 ──────────────────────────────────────────────────────────
    def _send(self, code, body, ctype):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, ensure_ascii=False), "application/json; charset=utf-8")

    def _file(self, path, ctype):
        try:
            with open(path, "rb") as f:
                self._send(200, f.read(), ctype)
        except OSError as exc:
            self._json({"error": str(exc)}, 500)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 1 << 20:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    # ── GET ──────────────────────────────────────────────────────────────────
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        route = parsed.path

        if route in ("/", "/index.html"):
            return self._file(INDEX, "text/html; charset=utf-8")
        if route == "/catalog":
            return self._file(CATALOG, "application/json; charset=utf-8")

        if route == "/api/state":
            state = read_state()
            env, running = RUNNER.snapshot_env()
            state["pad_env"] = env
            state["running"] = running
            # 서버가 기억하는 env 와 컨테이너에 실제로 적용된 값이 다르면 화면이 표시한다.
            applied = state.get("container_env") or {}
            state["env_drift"] = {
                k: {"pad": v, "container": applied.get(k)}
                for k, v in env.items()
                if applied and applied.get(k) != v
            }
            return self._json(state)

        if route == "/api/alerts":
            try:
                data = prom_get("/api/v1/alerts")["data"]["alerts"]
            except Exception as exc:
                return self._json({"error": f"Prometheus 에 닿지 않는다: {exc}"})
            firing = sorted({a["labels"]["alertname"] for a in data if a["state"] == "firing"})
            pending = sorted({a["labels"]["alertname"] for a in data if a["state"] == "pending"})
            return self._json({"firing": firing, "pending": pending})

        if route == "/api/log":
            qs = urllib.parse.parse_qs(parsed.query)
            try:
                since = int(qs.get("since", ["0"])[0])
            except ValueError:
                since = 0
            return self._json(RUNNER.log(since))

        return self._json({"error": "없는 경로"}, 404)

    # ── POST ─────────────────────────────────────────────────────────────────
    def do_POST(self):
        route = urllib.parse.urlparse(self.path).path
        try:
            body = self._body()
        except Exception as exc:
            return self._json({"error": f"본문을 읽지 못했다: {exc}"}, 400)

        if route == "/api/query":
            queries = body.get("queries") or []
            if not isinstance(queries, list) or len(queries) > 60:
                return self._json({"error": "queries 는 60개 이하의 배열이어야 한다"}, 400)
            results = []
            for q in queries:
                if not isinstance(q, str):
                    results.append({"query": str(q), "value": None})
                    continue
                try:
                    data = prom_get("/api/v1/query", {"query": q})["data"]["result"]
                    # 결과 없음은 null 이다. 0 으로 뭉개면 "없는 시계열"과 "0" 이 같아진다.
                    results.append({"query": q, "value": data[0]["value"][1] if data else None})
                except Exception as exc:
                    # Prometheus 가 죽어 있어도 화면은 깨지지 않아야 한다.
                    return self._json({"error": f"Prometheus 에 닿지 않는다: {exc}"})
            return self._json({"results": results})

        if route == "/api/run":
            action = body.get("action")
            args = body.get("args") or {}
            if not isinstance(action, str) or not isinstance(args, dict):
                return self._json({"error": "action(문자열)과 args(객체)가 필요하다"}, 400)
            if action not in ALLOW.params:
                return self._json({"error": f"허용 목록에 없는 액션: {action}"}, 400)
            started, err = RUNNER.start(action, args)
            if started is None:
                # 검증 실패는 400, 동시 실행은 409.
                code = 409 if err and err.startswith("이미 실행 중") else 400
                return self._json({"error": err}, code)
            return self._json({"started": started})

        return self._json({"error": "없는 경로"}, 404)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="장애 주입 버튼 패드")
    ap.add_argument("--port", type=int, default=8090)
    opts = ap.parse_args()

    catalog = load_catalog()
    ALLOW = Allowlist(catalog)
    RUNNER = Runner(ALLOW, catalog["env_defaults"])

    # 127.0.0.1 에만 바인드한다. 이 패드는 프로덕션을 죽일 수 있는 버튼 묶음이다.
    httpd = ThreadingHTTPServer(("127.0.0.1", opts.port), Handler)
    print(f"장애 주입 버튼 패드 → http://127.0.0.1:{opts.port}  (Prometheus {PROM})")
    print("Ctrl-C 로 종료한다. 스택은 따로 내려야 한다.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n종료한다")
