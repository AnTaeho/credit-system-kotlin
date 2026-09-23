// PERF-04 — 원장이 1억 행일 때 "최근 내역 조회" 의 API 지연.
//
// 다른 시나리오와 달리 **읽기만** 한다. 재는 것은 커서 페이징 엔드포인트 하나다:
//   GET /api/ledger            (첫 페이지)
//   GET /api/ledger?cursor=... (다음 페이지)
//
// SQL 직접 측정(load/measure-ledger-read.sh)과 나란히 봐야 한다 — 그쪽은 DB 안의 비용만,
// 이쪽은 인증 트랜잭션·JPA·직렬화까지 포함한 사용자가 겪는 값이다.
//
// 대상 계정은 원장이 큰 사용자여야 한다. 시드가 만든 사용자에 이메일을 붙여 쓴다.
//   USERS='bigledger@local.test' k6 run load/k6/05-ledger-read.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, DURATION, RUN_ID, USERS, authHeaders, SUMMARY_TREND_STATS } from './lib/common.js';

const READ_RATE = Number(__ENV.READ_RATE || 100);
const PAGE_SIZE = Number(__ENV.PAGE_SIZE || 20);

export const firstPage = new Trend('ledger_first_page', true);
export const nextPage = new Trend('ledger_next_page', true);
export const readOk = new Counter('ledger_read_ok');
export const readBad = new Counter('ledger_read_bad');

export const options = {
    scenarios: {
        read: {
            executor: 'constant-arrival-rate',
            rate: READ_RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.max(10, Math.ceil(READ_RATE * 0.3)),
            maxVUs: Math.max(50, READ_RATE * 3),
            exec: 'readLedger',
        },
    },
    thresholds: {
        // PERF-04 의 목표를 그대로 건다.
        'ledger_first_page': ['p(99)<50'],
        'ledger_next_page': ['p(99)<50'],
        'ledger_read_bad': ['count==0'],
    },
    summaryTrendStats: SUMMARY_TREND_STATS,
};

// 커서를 VU 안에 들고 다닌다. 첫 요청은 첫 페이지, 그다음은 받은 커서로 이어 읽는다.
let cursor = null;

export function readLedger() {
    const user = USERS[0];
    const url = cursor === null
        ? `${BASE_URL}/api/ledger?size=${PAGE_SIZE}`
        : `${BASE_URL}/api/ledger?size=${PAGE_SIZE}&cursor=${cursor}`;

    const res = http.get(url, { headers: authHeaders(user), tags: { name: 'ledger', run: RUN_ID } });

    const ok = check(res, { 'status 200': (r) => r.status === 200 });
    if (!ok) {
        readBad.add(1);
        cursor = null;
        return;
    }
    readOk.add(1);

    if (cursor === null) firstPage.add(res.timings.duration);
    else nextPage.add(res.timings.duration);

    // 다음 커서를 뽑는다. 끝에 닿으면 처음으로 되돌아가 계속 읽는다.
    let body;
    try {
        body = res.json();
    } catch (e) {
        cursor = null;
        return;
    }
    cursor = body && body.nextCursor ? body.nextCursor : null;
}

export function handleSummary(data) {
    const t = (name) => {
        const m = data.metrics[name];
        if (!m || !m.values) return '없음';
        const v = m.values;
        return `p50=${(v['p(50)'] ?? 0).toFixed(2)} p95=${(v['p(95)'] ?? 0).toFixed(2)} ` +
               `p99=${(v['p(99)'] ?? 0).toFixed(2)} max=${(v.max ?? 0).toFixed(2)}`;
    };
    const c = (name) => (data.metrics[name] && data.metrics[name].values.count) || 0;

    const lines = [
        '',
        '── 05 원장 조회 (PERF-04) ──',
        '',
        `  첫 페이지   ${t('ledger_first_page')}`,
        `  다음 페이지 ${t('ledger_next_page')}`,
        '',
        `  성공 ${c('ledger_read_ok')} · 실패 ${c('ledger_read_bad')}`,
        `  누락(dropped_iterations) ${c('dropped_iterations')}`,
        '',
        '  PERF-04 = 두 지연의 p99 < 50ms. 단위는 ms.',
        '',
    ];
    return { stdout: lines.join('\n') };
}
