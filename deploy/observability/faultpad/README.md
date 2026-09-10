# 장애 주입 버튼 패드

가만히 두면 아무 지표도 쌓이지 않는다. 이 패드는 **사고를 심는 버튼**과 **그 사고가 심겼을 때
반응해야 할 지표·침묵해야 할 지표**를 한 화면에 놓는다. Grafana 를 옆에 띄워 두고 버튼을
누르면서 곡선을 보는 용도다.

버튼 하나가 사고 하나다. 시나리오 스크립트를 통째로 돌리는 것이 아니라, 누르면 즉시 반응하고
원하는 만큼 두고 보다가 짝이 되는 복구 버튼을 누른다.

## 전제

- Docker (Compose v2), python3 (표준 라이브러리만 쓴다 — 설치할 것이 없다)
- 8090(패드) · 8080(앱) · 9090(Prometheus) · 3000(Grafana) 포트를 쓴다

## 띄우기

```
python3 deploy/observability/faultpad/server.py          # 기본 포트 8090
python3 deploy/observability/faultpad/server.py --port 8099
```

<http://127.0.0.1:8090> 을 연다. 서버는 **127.0.0.1 에만 바인드한다** — 프로덕션을 죽일 수 있는
버튼 묶음이라 네트워크에 내놓지 않는다.

스택은 패드의 `스택 올리기 (fresh)` 버튼으로 올려도 되고, 미리 올려 둬도 된다.
끝나면 `스택 내리기 (down -v)` 를 누르고 서버는 Ctrl-C 로 끈다.

## 구성

| 파일 | 하는 일 |
|---|---|
| `server.py` | 정적 서빙 + `actions.sh` 실행(동시에 하나만) + Prometheus 프록시. 현재 `APP_*` env 집합을 메모리에 들고 있다 |
| `actions.sh` | `scenarios/lib.sh` 를 source 한다. docker·mysql·curl 조작은 전부 여기서만 한다 |
| `catalog.json` | 카드·버튼·기대 지표 정의. 서버의 **액션 허용 목록**이자 화면의 데이터 |
| `index.html` | 단일 파일. 외부 리소스 0 — 오프라인에서도 뜬다 |
| `.state/` | 05 원장 훼손의 원복 정보. `.gitignore` 에 있다 |

## 읽는 법

- **상단 상태 띠** — app/redis/mysql/prometheus 생사, DB 의 job 상태별 카운트와 미결 수,
  잔액, Redis ZSET 크기, 컨테이너에 실제 적용된 `APP_*`, 앱 가동 시간(재기동 = 카운터 리셋),
  firing/pending 알람. **DB 카운트를 게이지 옆에 두는 이유**는 04 카드에 있다 — 스냅샷이 한 번도
  안 돌면 게이지는 0 에 얼어붙는데, 그 0 은 "미결이 없다"가 아니라 "아직 아무도 안 셌다"다.
- **카드의 "지금 값"** — `없음(시계열 없음)` 과 `0` 은 다르다. 전자는 그 시계열이 아예 없다는
  뜻이고, 후자는 값이 0 이라는 뜻이다. 이 구분이 이 챕터의 논지다.
- **하단 로그** — `actions.sh` 의 stdout 이 그대로 흐른다. 실행 중에는 버튼이 전부 잠긴다.

## 안전장치

- `/api/run` 은 `catalog.json` 의 버튼에 선언된 액션만 받는다. 없는 액션은 400, 실행 중이면 409.
- 숫자 파라미터는 정수로 검증하고 범위를 확인한다.
- env 는 **catalog 에 선언된 조합**만 받고, 키는 `APP_*` 다섯 개로 제한된다.
- 브라우저가 보낸 문자열이 셸에 닿는 경로는 없다.

## 문법 검사

```
bash -n deploy/observability/faultpad/actions.sh
python3 -m py_compile deploy/observability/faultpad/server.py
python3 -m json.tool deploy/observability/faultpad/catalog.json > /dev/null
```

카드의 기댓값은 [`docs/step7-observability.md`](../../../docs/step7-observability.md) 6단계
결과 매트릭스와 후속 1 실측에서 그대로 옮긴 것이다. **08(외부 API 전부 실패)만 실측이 없어
화면에 "예상(미실측)" 이라고 표시된다.**
