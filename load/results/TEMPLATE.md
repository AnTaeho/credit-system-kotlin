# 부하 결과 — <YYYY-MM-DD> <시나리오>

> 이 파일을 `load/results/YYYY-MM-DD-<시나리오>.md` 로 복사해 채운다.
> **빈칸을 남기지 마라.** 모르는 칸에는 "미측정" 또는 "모름"이라고 적는다 —
> 빈칸은 나중에 "쟀는데 좋았다"로 읽힌다.

| | |
|---|---|
| 시나리오 | 01 Tier A / 02 핫 계정 / 03 외부 지연 / 04 생존 스파이크 |
| 요구서 항목 | PERF-0? |
| 실행 시각 | YYYY-MM-DD HH:MM ~ HH:MM (KST) |
| 스크립트 | `load/k6/??.js` |
| 명령 | `k6 run ...` (환경변수 포함, 그대로) |
| `RUN_ID` | |
| k6 종료 코드 | 0 = 전체 threshold 통과 / 99 = 하나 이상 실패 |

## 1. 판정

| 항목 | 목표 | 실측 | 통과 |
|---|---|---|---|
| `POST /api/jobs` p50 | < 20ms | | |
| `POST /api/jobs` p99 | < 100ms | | |
| `GET /api/users/me/balance` p99 | < 30ms | | |
| `GET /api/ledger` p99 | < 30ms | | |
| (02) 핫 p99 ÷ 균등 p99 | < 3배 | | |
| (04) `phase:recovered` p50 / p99 | < 20ms / < 100ms | | |
| (04) `phase:recovery` p50 / p99 (배수 구간, 참고) | 판정 없음 | | — |
| (04) `phase:spike` p50 / p99 (스파이크, 참고) | 판정 없음 | | — |

한 줄 결론:

## 2. 환경 — 이 런이 무엇을 쟀는가

| | |
|---|---|
| 앱 런타임 | 호스트 JVM / `java -version` 출력 |
| Spring 프로파일 | `local` |
| 스텁 프로파일 | (a) 기본 3000~7000ms·실패율 0.3 / (b) ...ms·실패율 ... |
| `APP_STUB_MINDELAYMILLIS` 등 실제 값 | |
| 프로파일이 먹었다는 근거 | README 1-6 의 적체 소진 관찰 결과 |
| DB | compose `credit-system-kotlin-mysql-1` / homebrew mysqld (**어느 쪽인지 반드시**) |
| Redis | compose / homebrew |
| Hikari 최대 커넥션 | 10 (기본값. 바꿨다면 이 런은 무효다) |
| `@@max_connections` | |
| `@@innodb_flush_log_at_trx_commit` / `@@sync_binlog` | / |
| 계정 수 / 목록 | |
| 핫 계정 (02) | |
| 시드 총액 / 계정당 | |
| 원장 행 수(시작 시점) | `SELECT COUNT(*) FROM ledger_entries` |
| 같이 떠 있던 다른 컨테이너 | (있으면 이름과 포트. CPU 를 나눠 쓴다) |
| 인증 경로 | **개발 로그인 헤더**(`X-Dev-User`). 운영의 구글 OIDC 세션 경로와 요청당 DB 접근이 다르다 |

## 3. 부하 발생기 — 이 수치가 시스템의 것인가

| | |
|---|---|
| 00 천장 측정 결과 | 목표 ??? RPS / 실제 ??? RPS |
| `dropped_iterations` | (0 이 아니면 k6 가 목표 RPS 를 못 낸 것이다) |
| `vus_max` / "insufficient VUs" 경고 | |
| `ulimit -n` | |
| 판단 | 이 런의 수치는 시스템의 것이다 / 발생기 몫이 섞였다 |

## 4. k6 요약 원문

```
(k6 출력을 그대로 붙인다 — thresholds / 지연 / 응답 분류 / 재시도 / 부하 발생기 전부)
```

409 를 갈라 센 결과:

| 카운터 | 값 | 뜻 |
|---|---|---|
| `hold_ok` | | 새 접수 |
| `hold_duplicate_ok` | | 200 duplicate=true — 멱등 동작(성공) |
| `hold_conflict_in_progress` | | 409 DUPLICATE_IN_PROGRESS — 멱등 동작(성공) |
| `insufficient_balance` | | **0 이어야 한다.** 0 이 아니면 시드 실패이고 이 런은 무효다 |
| `unauthorized` | | **0 이어야 한다.** 0 이 아니면 허용 목록 설정 실패다 |
| `server_error` / `transport_error` | / | |
| `retries` | | 같은 idemKey 로 다시 쏜 횟수 |
| `retry_ok_duplicate` | | 재시도가 duplicate=true 를 받았다 — **첫 시도가 이미 커밋됐고 중복 차감은 없었다(INV-03)** |
| `retry_ok_new` | | 재시도가 새로 접수됐다 — 첫 시도는 커밋되지 않았다 |
| `retry_exhausted` | | 재시도를 다 쓰고도 못 받았다 |
| `unexpected_duplicate` | | **0 이어야 한다.** 새 UUID 에 duplicate=true 가 왔다 |
| (04) `spike_accepted` | | 스파이크 구간에서 접수가 받아들여진 건수 — 생존의 최소 증거 |

## 5. 포화 관측 — 요구서 2-2 의 6항목

**여섯 칸 전부 채운다.** 마지막 칸이 Tier B 외삽의 유일한 근거다.

| # | 항목 | 어떻게 읽었나 | 값 |
|---|---|---|---|
| 1 | 앱 JVM CPU | `top -pid <pid>` 또는 `ps` 최대치 | |
| 2 | DB 컨테이너 CPU | `docker stats` (mysql 컨테이너) 최대치 | |
| 3 | Hikari 커넥션 대기 | `hikaricp_connections_pending` / `_active` / `_acquire_seconds_max` — **안 나오면 "미노출"** | |
| 4 | MySQL 락 대기 | `Innodb_row_lock_waits` 델타, `Innodb_row_lock_time_avg`, `sys.innodb_lock_waits` | |
| 5 | 디스크 fsync 관련 설정 | `innodb_flush_log_at_trx_commit` / `sync_binlog` 값과 그 뜻(커밋마다 fsync 인가) | |
| 6 | **무엇이 먼저 포화됐는가** | 위 다섯을 놓고 한 줄로. 모르면 "모른다" | |

Hikari 지표 노출 여부(요구서 2-2 미확인 항목의 답):

- [ ] `hikaricp_*` 가 `/actuator/prometheus` 에 나온다 → 위 3번에 값을 적었다
- [ ] 안 나온다 → **"미노출"**. 대신 앱 로그의 `Connection is not available` 건수: ___

## 6. 게이지 — PERF-07

15초 간격 스크레이프(README 4-1). 부하 중 + 부하 제거 후 15분.

| 시점 | `credit_hold_outstanding_count` | `credit_hold_outstanding_amount` | `credit_job_oldest_pending_age_seconds` |
|---|---|---|---|
| 부하 시작 | | | |
| 부하 중 최대 | | | |
| 부하 종료 시점 | | | |
| 종료 +5분 | | | |
| 종료 +15분 | | | |

- 분당 감소량(종료 +0 → +15분): **−___ 건/분**
- 판정: **단조 감소 확인 / 미확인**. **완전 소진은 미측정 — 산술상 83~194시간(요구서 3-2).**
- 실제로 노출된 메트릭 이름이 README 4-2 의 예상과 달랐다면 여기에 적는다:
- `credit_snapshot_staleness_seconds` 최대값(30 을 넘으면 스냅샷 자체가 멈춘 것이다):

INV-04b:

| | 시작 | 종료 | 비율 |
|---|---|---|---|
| `credit_generation_external_calls_total` | | | |
| `credit_generation_external_duplicate_calls_total` | | | duplicate ÷ calls = |

## 7. 부하 직후 불변식 검사

`./gradlew postLoadCheck` 출력 **전문**:

```
```

| 검사 | 결과 |
|---|---|
| INV-01 음수 잔액 | ___ 건 (0 이어야 한다) |
| INV-02 (a) `balance == initialBalance + Σledger` | ___ 건 불일치 |
| INV-02 (b) HOLD 원장 없는 job | ___ 건 |
| INV-02 (c) 미정산 종결 job | ___ 건 |

## 8. 해석과 넘기는 것

- 이 런이 **증명한 것**:
- 이 런이 **증명하지 못한 것**(측정 방법의 한계):
- 목표에 못 닿았다면: 그것은 측정의 결함이 아니라 **이 구조가 목표를 못 내는 것**이다(요구서 1-4). 그대로 적는다.
- Phase 2 로 넘기는 항목:
