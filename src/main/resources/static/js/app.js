// 화면 공통. CSP 때문에 모든 스크립트는 이 디렉터리의 파일로만 둔다.
// 서버 값·사용자 입력은 textContent 로만 넣는다(innerHTML 금지).
'use strict';

window.App = (function () {
  const TERMINAL = new Set(['COMPLETED', 'REFUNDED']);
  // FAILED 는 끝이 아니다. 워커가 재시도하거나 최종 환불(REFUNDED)로 넘긴다.
  const POLL_INTERVAL_MS = 2000;

  function meta(name) {
    const el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  // 9-B 의 CSRF 는 세션 저장소다. 쿠키가 아니라 서버가 그려 준 meta 에서 읽는다.
  function csrfHeaders() {
    const header = meta('_csrf_header');
    const token = meta('_csrf');
    const headers = {};
    if (header && token) headers[header] = token;
    return headers;
  }

  function relogin() {
    window.location.href = '/login?expired';
  }

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    // randomUUID 는 보안 컨텍스트(https·localhost)에서만 있다. 그 밖(예: LAN IP 의 http)을 위한 대체.
    const b = window.crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, function (x) { return x.toString(16).padStart(2, '0'); }).join('');
    return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
  }

  // 멱등키 한 제출분. 같은 내용을 다시 보내면(재시도·더블클릭) 같은 키, 내용이 바뀌면 새 키.
  // 서버가 확정 응답(성공·검증 실패 등)을 주면 done() 으로 다음 제출용 새 키를 준비한다.
  function IdemKey() {
    let key = uuid();
    let content = null;
    return {
      keyFor: function (c) {
        if (content !== null && content !== c) key = uuid();
        content = c;
        return key;
      },
      done: function () {
        key = uuid();
        content = null;
      }
    };
  }

  // fetch 래퍼. 결과는 { status, body, retryAfter }. 네트워크 오류면 status 0.
  // 401(세션 만료)은 여기서 곧장 다시 로그인으로 보낸다.
  async function api(method, url, body) {
    const headers = { 'Accept': 'application/json' };
    if (method !== 'GET') {
      Object.assign(headers, csrfHeaders());
      headers['Content-Type'] = 'application/json';
    }
    let res;
    try {
      res = await fetch(url, {
        method: method,
        headers: headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'same-origin',
        redirect: 'manual'
      });
    } catch (e) {
      return { status: 0, body: null, retryAfter: null };
    }
    if (res.status === 401) {
      relogin();
      return { status: 401, body: null, retryAfter: null, relogin: true };
    }
    let parsed = null;
    try { parsed = await res.json(); } catch (e) { parsed = null; }
    const result = { status: res.status, body: parsed, retryAfter: res.headers.get('Retry-After') };
    if (res.status === 403 && method !== 'GET') {
      // POST 의 403 은 두 가지다. 세션이 끝나 CSRF 토큰이 무효가 된 경우(CSRF 필터가 인증보다 먼저 막는다)와
      // 진짜 권한 부족. 둘 다 code 가 FORBIDDEN 이라 인증 여부를 한 번 더 물어 구분한다.
      const probe = await api('GET', '/api/users/me/balance');
      if (probe.relogin) result.relogin = true;
    }
    return result;
  }

  // 오류 응답을 사람이 읽을 문장으로. relogin 이면 이미 로그인으로 가는 중이다.
  function describeError(r) {
    if (r.status === 0) return '네트워크 오류입니다. 같은 요청으로 다시 시도할 수 있습니다.';
    if (r.status === 429) {
      const s = parseInt(r.retryAfter, 10);
      return (isNaN(s) ? '잠시' : s + '초') + ' 후 다시 시도하세요. (요청이 너무 잦습니다)';
    }
    if (r.status === 403) return '요청이 거부되었습니다. 권한이 없거나 페이지가 오래되었습니다. 새로고침 후 다시 시도하세요.';
    if (r.body && r.body.message) return r.body.message;
    return '요청을 처리하지 못했습니다. (HTTP ' + r.status + ')';
  }

  function show(el, text, isError) {
    if (!el) return;
    el.textContent = text;
    el.classList.toggle('error', !!isError);
  }

  function isTerminal(status) {
    return TERMINAL.has(status);
  }

  // 진행 중 job 을 끝날 때까지 폴링한다. 매번 onUpdate(job), 끝나면 onDone(job).
  function pollJob(jobId, onUpdate, onDone) {
    async function tick() {
      const r = await api('GET', '/api/jobs/' + encodeURIComponent(jobId));
      if (r.relogin) return;
      if (r.status === 200 && r.body) {
        onUpdate(r.body);
        if (isTerminal(r.body.status)) {
          onDone(r.body);
          return;
        }
      } else if (r.status === 404) {
        return;
      }
      setTimeout(tick, POLL_INTERVAL_MS);
    }
    setTimeout(tick, POLL_INTERVAL_MS);
  }

  async function refreshBalance() {
    const el = document.getElementById('balance');
    if (!el) return;
    const r = await api('GET', '/api/users/me/balance');
    if (r.status === 200 && r.body) el.textContent = String(r.body.balance);
  }

  function setText(root, selector, value) {
    const el = root.querySelector(selector);
    if (el) el.textContent = value === null || value === undefined ? '-' : String(value);
  }

  // job 한 행(표의 tr 또는 상세의 dl)에 값을 채운다. 결과 URL 은 텍스트로만 둔다(step12 에서 실제 결과물 표시로 바뀔 자리).
  function fillJob(root, job) {
    root.dataset.jobId = String(job.id);
    root.dataset.status = job.status;
    const link = root.querySelector('a.c-id');
    if (link) {
      link.textContent = String(job.id);
      link.setAttribute('href', '/jobs/' + encodeURIComponent(job.id));
    }
    setText(root, '.c-status', job.status);
    setText(root, '.c-prompt', job.prompt);
    setText(root, '.c-result', job.resultUrl);
    setText(root, '.c-updated', job.updatedAt);
    setText(root, '.c-attempt', job.attemptNo);
    setText(root, '.c-hold', job.holdAmount);
  }

  return {
    api: api,
    IdemKey: IdemKey,
    describeError: describeError,
    show: show,
    isTerminal: isTerminal,
    pollJob: pollJob,
    refreshBalance: refreshBalance,
    fillJob: fillJob,
    setText: setText
  };
})();
