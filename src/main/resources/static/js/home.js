'use strict';

(function () {
  const App = window.App;
  const form = document.getElementById('job-form');
  const prompt = document.getElementById('prompt');
  const button = form.querySelector('button[type="submit"]');
  const formMessage = document.getElementById('job-form-message');
  const rows = document.getElementById('job-rows');
  const template = document.getElementById('job-row-template');
  const more = document.getElementById('job-more');
  const listMessage = document.getElementById('job-list-message');
  const empty = document.getElementById('job-empty');
  const idem = App.IdemKey();
  const polling = new Set();

  function track(row) {
    const id = row.dataset.jobId;
    if (App.isTerminal(row.dataset.status) || polling.has(id)) return;
    polling.add(id);
    App.pollJob(id, function (job) { App.fillJob(row, job); }, function () {
      polling.delete(id);
      App.refreshBalance();
    });
  }

  function newRow(job) {
    const row = template.content.firstElementChild.cloneNode(true);
    App.fillJob(row, job);
    return row;
  }

  function findRow(jobId) {
    return rows.querySelector('tr[data-job-id="' + CSS.escape(String(jobId)) + '"]');
  }

  rows.querySelectorAll('tr.job-row').forEach(track);

  form.addEventListener('submit', async function (e) {
    e.preventDefault();
    if (button.disabled) return;
    const text = prompt.value;
    if (!text.trim()) {
      App.show(formMessage, '프롬프트를 입력하세요.', true);
      return;
    }
    button.disabled = true;
    App.show(formMessage, '요청 중…', false);
    try {
      const r = await App.api('POST', '/api/jobs', { idemKey: idem.keyFor(text), prompt: text });
      if (r.relogin) return;
      if (r.status === 200 && r.body) {
        idem.done();
        prompt.value = '';
        App.show(formMessage, r.body.duplicate ? '이미 접수된 요청입니다.' : '접수했습니다.', false);
        await showJob(r.body.jobId);
        App.refreshBalance();
        return;
      }
      // 400·409 는 서버가 확정적으로 거절한 것이다(흔적 없음). 다음 제출은 새 키로.
      // 네트워크 오류·429·5xx 는 같은 제출의 재시도가 될 수 있으니 키를 유지한다.
      if (r.status === 400 || r.status === 409) idem.done();
      App.show(formMessage, App.describeError(r), true);
    } finally {
      button.disabled = false;
    }
  });

  async function showJob(jobId) {
    const r = await App.api('GET', '/api/jobs/' + encodeURIComponent(jobId));
    if (r.status !== 200 || !r.body) return;
    let row = findRow(jobId);
    if (row) {
      App.fillJob(row, r.body);
    } else {
      row = newRow(r.body);
      rows.insertBefore(row, rows.firstElementChild);
    }
    if (empty) empty.hidden = true;
    track(row);
  }

  if (more) {
    more.addEventListener('click', async function () {
      const cursor = more.dataset.cursor;
      if (!cursor || more.disabled) return;
      more.disabled = true;
      try {
        const r = await App.api('GET', '/api/jobs?cursor=' + encodeURIComponent(cursor));
        if (r.relogin) return;
        if (r.status !== 200 || !r.body) {
          App.show(listMessage, App.describeError(r), true);
          return;
        }
        r.body.items.forEach(function (job) {
          if (findRow(job.id)) return;
          const row = newRow(job);
          rows.appendChild(row);
          track(row);
        });
        if (r.body.nextCursor === null || r.body.nextCursor === undefined) {
          more.hidden = true;
        } else {
          more.dataset.cursor = String(r.body.nextCursor);
        }
        App.show(listMessage, '', false);
      } finally {
        more.disabled = false;
      }
    });
  }
})();
