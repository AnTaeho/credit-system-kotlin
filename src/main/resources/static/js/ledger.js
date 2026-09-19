'use strict';

(function () {
  const App = window.App;
  const rows = document.getElementById('ledger-rows');
  const template = document.getElementById('ledger-row-template');
  const more = document.getElementById('ledger-more');
  const message = document.getElementById('ledger-message');
  if (!more) return;

  more.addEventListener('click', async function () {
    const cursor = more.dataset.cursor;
    if (!cursor || more.disabled) return;
    more.disabled = true;
    try {
      const r = await App.api('GET', '/api/ledger?cursor=' + encodeURIComponent(cursor));
      if (r.relogin) return;
      if (r.status !== 200 || !r.body) {
        App.show(message, App.describeError(r), true);
        return;
      }
      r.body.items.forEach(function (e) {
        const row = template.content.firstElementChild.cloneNode(true);
        App.setText(row, '.c-id', e.id);
        App.setText(row, '.c-type', e.type);
        App.setText(row, '.c-amount', e.amount);
        App.setText(row, '.c-job', e.jobId);
        App.setText(row, '.c-created', e.createdAt);
        rows.appendChild(row);
      });
      if (r.body.nextCursor === null || r.body.nextCursor === undefined) {
        more.hidden = true;
      } else {
        more.dataset.cursor = String(r.body.nextCursor);
      }
      App.show(message, '', false);
    } finally {
      more.disabled = false;
    }
  });
})();
