'use strict';

(function () {
  const App = window.App;
  const detail = document.getElementById('job-detail');
  if (!detail || App.isTerminal(detail.dataset.status)) return;
  App.pollJob(detail.dataset.jobId, function (job) { App.fillJob(detail, job); }, function () {});
})();
