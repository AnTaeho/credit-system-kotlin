'use strict';

(function () {
  const App = window.App;
  const form = document.getElementById('grant-form');
  const userId = document.getElementById('grant-user-id');
  const amount = document.getElementById('grant-amount');
  const button = form.querySelector('button[type="submit"]');
  const message = document.getElementById('grant-message');
  const idem = App.IdemKey();

  form.addEventListener('submit', async function (e) {
    e.preventDefault();
    if (button.disabled) return;
    const target = userId.value.trim();
    const value = amount.value.trim();
    if (!/^[1-9][0-9]*$/.test(target) || !/^[1-9][0-9]*$/.test(value)) {
      App.show(message, '대상 userId 와 금액은 1 이상의 정수여야 합니다.', true);
      return;
    }
    button.disabled = true;
    App.show(message, '지급 중…', false);
    try {
      const key = idem.keyFor(target + ':' + value);
      const r = await App.api('POST', '/api/admin/users/' + encodeURIComponent(target) + '/grants',
        { idemKey: key, amount: Number(value) });
      if (r.relogin) return;
      if (r.status === 200 && r.body) {
        idem.done();
        App.show(message, (r.body.duplicate ? '이미 반영된 지급입니다. ' : '지급했습니다. ') +
          'userId ' + target + ' 의 잔액: ' + r.body.balance, false);
        return;
      }
      if (r.status === 400 || r.status === 404 || r.status === 409) idem.done();
      App.show(message, App.describeError(r), true);
    } finally {
      button.disabled = false;
    }
  });
})();
