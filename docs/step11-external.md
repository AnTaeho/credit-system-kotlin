# step11-external — 외부 호출 안전화

step9 까지 이 서비스의 "외부 호출"은 `Thread.sleep` 이었다. 인메모리 시뮬레이션이라 **느려질 수도, 죽을 수도, 영원히 돌아오지 않을 수도 없었다.** 그래서 타임아웃도 재시도 간격도 한 번도 검증된 적이 없다. step12 에서 진짜 Claude 를 붙이는 순간 그 셋이 전부 동시에 실전이 된다.

step11 은 진짜 외부를 붙이기 **전에** 그 경계를 만들고, 가짜 생성기로 최악의 경우를 실제로 재현해 잰다. 외부가 느려도, 죽어도, 영원히 응답하지 않아도 돈이 묶이지 않는다는 것을 숫자로 남기는 단계다.

- 이전 단계: `step9-auth` (step10 배포는 보류했다 — [로드맵](roadmap.md) step10 절)
- 로드맵: [`docs/roadmap.md`](roadmap.md) 의 step11. 근거가 되는 결정은 11(재시도는 attemptNo 한 곳에서만), 13(서버 1대), 그리고 2026-09-20 의 두 확정(멈춘 워커는 백스톱 절대 상한, 재시도는 지수 backoff)

## 이전 단계의 문제

| 영역 | 그때 | 왜 문제인가 |
|---|---|---|
| 타임아웃 | 없음. 스텁이 자기 지연만큼 자고 돌아왔다 | 외부가 10분을 끌면 워커 스레드도 10분 묶인다. 상한이 없으면 "느린 외부"와 "죽은 외부"가 구분되지 않는다 |
| 멈춘 워커 | 회수 그물 둘 다 놓친다 | 종지기 스레드는 워커 상태를 보지 않고 5초마다 갱신한다. 워커가 hang 이면 heartbeat 는 영원히 LIVE 고, 만료 그물도 정체 백스톱도(LIVE 면 건너뛴다) 잡지 못한다. **돈이 held 에 영구히 묶였다** |
| 재시도 간격 | 없음. 회수된 바퀴에서 바로 HOLDING | 외부가 죽어 있으면 상한 3회를 몇 초 만에 태우고 외부 호출 비용만 3번 나간다 |
| 배포 종료 | 드레인 없음(`step8-b-drain-archive` 에 보관) | 배포할 때마다 진행 중 job 이 회수로 넘어간다. 안전망을 정상 경로로 쓰는 셈이다 |
| 로그 | 텍스트 로그, 상관 ID 없음 | job 하나가 HTTP → 워커 → 회수를 거치는데 그 줄들을 묶을 식별자가 없다 |
| 프롬프트 | 로그·예외 메시지에 그대로 실린다 | 예외 메시지는 로그로 번져 나가고, 프롬프트는 사용자가 쓴 내용이다 |

## 무엇을 왜 바꿨나

커밋 순서대로. 조각 이름(A~F)과 커밋 순서가 같다.

**A — 생성 클라이언트 경계와 호출 타임아웃 (`9494faa`)**

`job/generation` 에 포트(`GenerationClient`, `GenerationException`, `GenerationTimeoutException`), `job/generation/stub` 에 어댑터를 둔다. 워커는 스텁 패키지를 더 이상 import 하지 않는다 — step12 에서 진짜 Claude 구현이 같은 자리에 들어온다.

- **타임아웃은 경계의 구현이 소유한다**(`app.generation.timeout-seconds`, 기본 20). 호출자가 감시 스레드로 감싸지 않으므로, 인터럽트 플래그를 남긴 채 풀로 돌아가는 함정이 구조적으로 없다. 진짜 구현에서는 같은 값이 SDK 클라이언트의 타임아웃 설정이 된다
- **타임아웃은 생성 실패와 같은 경로**다(`markFailed` → 재시도 → 최종 환불). 로그에서만 구분한다 — 실패율이 오른 것과 외부가 느려진 것은 대응이 다른 사건이라서다
- 장애 주입 손잡이 둘: 지연이 상한을 넘으면 타임아웃, `app.stub.hang=true` 면 **타임아웃조차 먹지 않고** 영원히 돌아오지 않는다(기본 꺼짐). hang 은 다음 조각의 대상이다

**B — 백스톱 절대 상한 (`3075367`)**

hang 구멍을 막는다. `app.processing.absolute-timeout-seconds`(기본 300). heartbeat 가 LIVE 여도 PROCESSING 의 `updatedAt` 이 이 상한을 넘으면 회수하고, 감지자를 `HARD_CAP` 으로 센다(detector 태그 값 3 → 4).

- 반드시 `timeout-seconds` 보다 커야 하고, 아니면 기동이 거부된다. 같거나 작으면 절대 상한이 후보 선정 기준을 덮어써서 백스톱이 아니라 그냥 짧은 타임아웃이 된다
- **오회수 비용을 정직하게 적었다.** 정상인데 아주 느린 job 도 잡는다. 잘못 내려도 attemptNo CAS 가 돈을 지키고(원래 워커의 전이가 0행), 대가는 낭비된 외부 호출 1회다. 반대로 상한이 없으면 대가는 영구히 묶인 돈이다
- **이 장치는 돈만 푼다.** 멈춘 워커 스레드는 돌아오지 않는다. 그 누수를 `credit.worker.slots.free` 게이지(태그 없음)로 보이게 했다

**C — 재시도 지수 backoff (`e17ed27`)**

Flyway V5 가 `jobs.next_attempt_at`(NULL = 지금 가능)을 더하고, 회수 뒤 재시도가 `now + base * multiplier^(재시도-1)` 을 채운다. 디스패처는 때가 된 job 만 집는다.

- 기본 10초·4배·상한 300초 → `max-attempts` 가 3 이라 **실제로 쓰이는 값은 10초와 40초 두 번**이다
- 인덱스는 일부러 만들지 않았다. `IS NULL OR <=` 는 OR 조건이라 이득이 계획에 달렸고, 사용자 1명 규모에선 쓰기 비용만 확실히 는다(마이그레이션 주석)
- 최종 환불 판정은 FAILED 만 보므로 backoff 가 환불을 늦추지 않는다
- 건드린 두 클래스의 `Instant.now()` 를 주입된 `Clock` 으로 통일했다

**D — 드레인 복원 (`e512b76`)**

배포는 예고된 종료다. 그때마다 회수(안전망)를 타면 재시도 한 번과 외부 호출 비용이 그대로 나간다. step8 에서 만들어 두고 배포할 곳이 없어 `step8-b-drain-archive` 에 보관했던 구현을 지금 코드 위로 살렸다(`3bdbbf2`·`3b4a8ce` cherry-pick + 11-C 의 `Clock`·step9 의 인증에 맞춰 정리).

**문은 플래그가 아니라 락이다.** 진행 중인 디스패치 한 바퀴가 끝나야 닫히므로 "닫힌 뒤엔 새 선점이 없다"가 상태가 아니라 구조로 보장된다.

**E — 상관 ID 와 구조화 로그 (`66e52e8`)**

- 요청 ID(HTTP 한 번)와 job 식별자(`jobId`·`attemptNo`, HTTP 밖)를 나눠 MDC 에 넣는다. 둘을 잇는 곳은 hold 성공 로그 한 줄 — 요청 ID 를 job 행에 저장하는 마이그레이션은 사용자 1명 규모에서 값어치가 작다
- 요청 ID 필터는 시큐리티 체인보다 앞이고 응답 헤더를 먼저 박는다. 401·CSRF 403 처럼 추적이 가장 필요한 응답에도 붙는다
- MDC 는 try/finally 로 복원한다. 풀 스레드 재사용 시 이전 `jobId` 가 따라붙지 않는 것을 테스트로 못 박았다
- 평문 프로필은 부트의 `correlation` 자리에 패턴만 채운다(로그 문장 불변). prod 만 `logging.structured.format.console=ecs` — 새 의존성 없음
- **프롬프트를 로그·예외 메시지에서 걷어내고 길이만 남긴다.** 어떤 요청이었는지는 `jobId` 로 따라간다

**F — 관측 시나리오·알람과 이 문서**

아래 "관측을 새 동작에 맞췄다" 절.

## 설정

step11 이 새로 만든 것 전부. 전부 기본값이 있고, 환경변수 이름은 Spring 완화 바인딩 규칙(점은 밑줄, 대시는 제거, 대문자)으로 만들어진다.

| 속성 | 기본값 | 환경변수 | 왜 그 값인가 |
|---|---|---|---|
| `app.generation.timeout-seconds` | 20 | `APP_GENERATION_TIMEOUTSECONDS` | 스텁 기본 지연(3~7초)의 3배쯤 위. 정상 요청이 걸리지 않으면서, 느린 외부를 워커가 20초 넘게 붙들지 않는다. step12 에서 Claude 의 실제 지연 분포를 보고 다시 정한다 |
| `app.generation.retry-backoff.base-seconds` | 10 | `APP_GENERATION_RETRYBACKOFF_BASESECONDS` | 첫 재시도 대기. 잠깐 튄 외부라면 10초면 돌아온다 |
| `app.generation.retry-backoff.multiplier` | 4 | `APP_GENERATION_RETRYBACKOFF_MULTIPLIER` | 2배는 `max-attempts` 3 안에서 간격이 거의 안 벌어진다(10 → 20). 4배면 10 → 40 이라 "오래가면 덜 자주"가 실제로 드러난다 |
| `app.generation.retry-backoff.max-seconds` | 300 | `APP_GENERATION_RETRYBACKOFF_MAXSECONDS` | 상한. `max-attempts` 3 에서는 닿지 않는다(10·40). 시도 상한을 늘릴 때를 위한 천장이다 |
| `app.processing.absolute-timeout-seconds` | 300 | `APP_PROCESSING_ABSOLUTE_TIMEOUT_SECONDS` | 정상 job 의 최대 체류(타임아웃 20초 + 여유)보다 훨씬 위여서 오회수가 드물고, 묶인 돈을 5분 안에는 푼다. `timeout-seconds`(60)보다 커야 하고 아니면 기동 거부 |
| `app.stub.hang` | false | `APP_STUB_HANG` | 장애 주입 전용. 타임아웃조차 먹지 않는 "응답 없음" 재현. 켜면 그 워커 스레드는 재기동 전까지 돌아오지 않는다 |

바꾸지 않았지만 step11 에서 뜻이 늘어난 것:

| 속성 | 값 | step11 에서 추가된 역할 |
|---|---|---|
| `app.processing.timeout-seconds` | 60 | 정체 회수의 **후보 선정 기준**이자 **드레인 상한**(D). 절대 상한의 하한이기도 하다. 하나를 바꾸면 셋이 함께 움직인다 |
| `spring.lifecycle.timeout-per-shutdown-phase` | `${app.processing.timeout-seconds:60}s` | 종료 phase 하나가 매달릴 수 있는 상한. 드레인 상한과 **같은 값에서 유도된다**(아래 한계) |

## 종료 순서와 드레인 상한

`SIGTERM` 이 오면 이 순서로 내려간다.

1. **`ContextClosedEvent`** — `@Scheduled` 를 굴리는 `taskScheduler` 가 여기서 `shutdown()` 된다. 주기 task 는 더 이상 재예약되지 않는다. 다만 *지금 돌고 있는* 디스패치 주기를 기다려 주지는 않아서, 그 창은 `WorkerDrainGate`(락)가 막는다
2. **`SmartLifecycle.stop`(phase 내림차순)** — 내장 웹서버가 먼저 멈춘다(`server.shutdown: graceful`). HTTP 유입이 끊긴 뒤에 드레인이 시작되므로 드레인 도중 새 job 이 생기지 않는다. 그다음 `GenerationWorkerLifecycle` 이 `ExecutorConfigurationSupport.DEFAULT_PHASE - 1` 에서 드레인한다 — 임의의 숫자가 아니라 상수에서 유도해, 스프링이 값을 바꾸면 따라가게 했다
3. **빈 소멸** — `HeartbeatRegistry.shutdown()` 이 여기서야 불린다. 즉 **드레인이 도는 동안 heartbeat 는 살아서 갱신된다.** 이 순서가 깨지면 살아 있는 job 의 heartbeat 가 먼저 끊겨 회수 대상이 된다

드레인 상한은 `app.processing.timeout-seconds`(60초)를 그대로 쓴다. 그 시간을 넘긴 PROCESSING job 은 어차피 정체 회수의 대상이라 더 기다려도 얻을 것이 없고, 별도 설정을 새로 만들면 두 값이 어긋날 자리가 생긴다.

컨테이너 쪽도 함께 맞춰야 한다. 도커 기본 유예는 10초뿐이라 그대로 두면 드레인이 잘린다 — 루트와 관측 스택 compose 모두 `stop_grace_period: 90s`(드레인 60초 + 웹 graceful shutdown phase 와 뒷정리 여유 30초)다.

## 관측을 새 동작에 맞췄다

### 07 을 둘로 나눴다

step7 의 `07-external-api-hang.sh` 는 스텁 지연을 600초로 올려 "무한 지연"을 흉내 냈다. 11-A 이후 그 사고는 **20초 타임아웃에 먼저 걸린다.** 스크립트 이름과 실제로 재는 것이 달라졌다.

- **`07-external-api-timeout.sh`** — 사고(600초 지연)는 **그대로 두고** 이름과 기대만 바꿨다. 같은 사고를 같은 방식으로 심어야 step7 과 나란히 놓을 수 있고, 그 두 줄의 차이가 곧 11-A 가 산 것이다
- **`08-worker-hang.sh`** (새로) — `APP_STUB_HANG=true`. 진짜 무응답. 이 시나리오에서만 `APP_PROCESSING_ABSOLUTE_TIMEOUT_SECONDS=90` 으로 낮춘다. 기본 300 이면 시도 3회에 회수만 15분이라 시나리오로 못 쓴다. 90 인 이유는 `timeout-seconds`(60)보다 커야 하기 때문이고, `timeout-seconds` 를 대신 낮추지 않은 이유는 그 값이 드레인 상한이기도 해서 시나리오와 무관한 것이 함께 움직이기 때문이다
- job 을 1건만 만든다. 3건이면 슬롯 3개가 동시에 묶여 첫 회수 직후 재시도가 디스패치되지 못하고, "돈이 풀린다"를 관측할 수 없다. 그 포화 상태는 마지막에 따로 만들어 **한계 그 자체로** 기록한다

`restart_app_with` 는 환경변수를 export 하지만, compose 는 `app.environment` 에 나열된 것만 보간한다. `APP_STUB_HANG` 과 `APP_PROCESSING_ABSOLUTE_TIMEOUT_SECONDS` 를 관측 compose 에 더했다 — 없으면 export 해도 컨테이너에 들어가지 않는다.

### 알람 둘을 더했다 (12개 → 14개)

| 알람 | 식 | 임계 근거 |
|---|---|---|
| `CreditHardCapRecovery` (P2) | `increase(credit_job_recovery_total{detector="hard_cap"}[10m]) > 0` | 절대 상한이 발화했다는 사실 자체가 "워커가 멈췄다"는 신호다. backstop 두 알람과 원인이 다르다 — 저 둘은 heartbeat 가 **안 보여서** 회수한 것이고, 이쪽은 heartbeat 가 "살아 있다"고 말하는데도 회수한 것이다. 세 규칙 모두 `detector` 값을 정확히 하나씩 짚으므로 겹치지 않는다 |
| `CreditWorkerSlotsExhausted` (P2) | `credit_worker_slots_free == 0` `for: 5m` | 11-B 의 한계를 사람에게 알리는 **유일한** 장치다. `for` 는 `absolute-timeout-seconds`(300초 = 5분)에서 유도한다 — 그 시간이면 묶인 job 은 절대 상한이 이미 회수해 슬롯이 풀렸어야 한다. 그러고도 0 이면 backlog 가 아니라 누수다. 기본값(concurrency 3, 타임아웃 20초)에서 정상 포화가 5분 연속 이어지려면 대기열이 45건 넘게 쌓여야 하는데 사용자 한 명 규모에선 일어나지 않는다 |

대시보드에는 "워커 슬롯" 행을 더했다(빈 슬롯 게이지 + `hard_cap` 회수율). 빈 슬롯 패널의 임계선은 1 이고 그 아래가 빨강이다 — 다른 패널과 방향이 반대인 유일한 패널이라서(작을수록 나쁘다) 색 규칙을 뒤집었다. 회수 패널은 원래 `sum by (detector)` 라 `hard_cap` 이 저절로 나타났고, 제목만 고쳤다.

### 시간 예산

backoff 가 job 당 최대 +50초(10 + 40)를 더한다. 종결을 기다리는 시나리오(06 의 180초, 01 의 240초)의 상한이 그 아래에서도 충분한지는 **재실행으로 확인해야 하고, 아직 확인하지 못했다.** 01 은 이번에 240초 안에 끝났지만(실측) 06 은 돌지 못했다. 지금은 상한을 손대지 않고 두었다 — 근거 없이 숫자를 키우면 다음 사람이 그 숫자가 어디서 왔는지 알 수 없다.

## 시나리오 재실행 — **중단됐다 (01·02 만 실측)**

2026-09-20 `run-all.sh` 를 돌렸으나 **03 진행 중(T+331) 실행이 외부에서 끊겼다.** 그래서 이 절에 적을 수 있는 실측은 01·02 뿐이다. 03~08 은, 특히 **이 단계의 논지를 지는 07(타임아웃)과 08(멈춘 워커)은 한 번도 끝까지 재지 못했다.**

아래 표에서 07·08 행을 비워 둔 것은 실수가 아니라 상태다. 채우려면 스택을 다시 올리고 `run-all.sh` 를 처음부터 돌려야 한다(40~60분).

| # | 이전 (step9 재실측 / step7) | 이번 | 판정 |
|---|---|---|---|
| 01 워커 크래시 | `recovery{heartbeat}` 3 / SIGKILL 후 14초(앱 UP 후 4초), `backstop` 0, 알람 없음 | 3 / SIGKILL 후 15초(앱 UP 후 8초), `backstop` 0, `retry_claim` 7, 알람 없음, 불변식 0 | **같음** |
| 02 A ZSET 소실 | `backstop` 3 (앱 UP 후 53초), `heartbeat` 0, `backstop_blind` 0 | 3 (50초 / SIGKILL 후 63초), 0, 0. `CreditBackstopRecovery` +2초 | **같음** |
| 02 B Redis 다운 | `backstop_blind` 3 (+8초), WARN 3회, 복구 후 추가 회수 없음, 워커 재개 후 7초 `COMPLETED=7` | 3 (+8초), WARN 3회·ERROR 9회, 추가 회수 없음, 6초 `COMPLETED=7` | **같음** |
| 03 워커 정지 | `CreditPipelineStalled` 383초, count 5 / amount 500 | T+331 에서 중단. 그 시점까지 count 5 / amount 500, age 328, `CreditPipelineStalled` **pending** | **미완** |
| 04 스케줄러 정지 | staleness -1, 두 Stale 알람 firing | — | **미실행** |
| 05 원장 훼손 | mismatch 39초 / negative 12초 / jobs_without_hold 12초 | — | **미실행** |
| 06 중복 폭풍 | `app_hit` 90 + `db_unique` 9 = 99, 잔액 -100, job 1행 | — | **미실행** |
| 07 외부 API 지연 → 타임아웃 | (step7·step9 의 값은 타임아웃이 없던 시절 것이라 더 이상 이 사고의 기대값이 아니다) | — | **미실행** |
| 08 멈춘 워커 | (새 시나리오) | — | **미실행** |

01·02 에서 **backoff 로 인한 초 단위 증가는 보이지 않았다.** 이 둘의 감지 지연은 heartbeat 만료(10초)와 정체 스캔 주기가 정하는 값이라 재시도 간격이 끼어들 자리가 없다. 02B 의 "워커 재개 후 종결까지"는 backoff 가 끼어들 수 있는 자리인데 6초로 이전(7초)과 같았다 — 재개 시점에 그 job 들이 이미 대기 시각을 넘긴 HOLDING 이었기 때문으로 보인다. 확정하려면 다시 재야 한다.

**따라서 아래 항목은 코드·주석·이 문서의 추론일 뿐 실측이 아니다.**

- 07 의 "종결까지 ~110초", `mark_failed` 9 / `retry_claim` 6 / `final_refund` 3, 슬롯 3 복귀
- 08 의 `hard_cap` 3, 환불까지 ~335초, 슬롯 3 → 0 이 돌아오지 않는 것, 새 job 이 HOLDING 에 갇히는 것, `CreditWorkerSlotsExhausted` 가 실제로 fire 되는 것
- 06(180초)·01(240초) 의 종결 대기 예산이 backoff(+최대 50초) 아래에서 충분한지

08 의 타임라인 예상은 스크립트 주석에 근거와 함께 적어 두었다. 절대 상한 90초·backoff 10·40초에서 회수 3회와 환불이 약 335초, 슬롯 누수 알람이 약 540초다. **이 숫자가 맞는지는 아직 아무도 확인하지 않았다.**


## 한계

- **절대 상한은 돈만 푼다.** 멈춘 워커 스레드를 깨울 수단이 없다. job 은 FAILED → 재시도 → 환불까지 흘러가고 잔액도 맞지만, 슬롯은 하나씩 **재기동 전까지 영구히** 사라진다. 그 손실은 job 상태 어디에도 적히지 않는다 — `credit_worker_slots_free` 말고는 볼 곳이 없고, 사람에게 알리는 것은 `CreditWorkerSlotsExhausted` 하나다
- **슬롯이 전부 묶이면 서비스가 멈춘다.** 돈은 풀렸는데 새 job 은 접수만 되고 디스패치되지 않아 HOLDING 에서 늙는다. 08 이 이것을 직접 만들어 기록한다. 복구는 재기동뿐이다
- **고아 heartbeat 가 ZSET 에 남는다.** 좀비의 종지기는 회수 시점에 멤버를 지워도 5초 뒤 다시 써넣는다(워커 스레드가 끝나야 `finally` 의 정리가 돈다). 계속 갱신되므로 만료 조회에 잡히지도 않는다. 무해한 이유는 그 job 이 이미 PROCESSING 이 아니라 `failIfProcessing` 이 0행을 돌려주기 때문이다. 고아 자체를 없애려면 멈춘 스레드를 깨울 수단이 있어야 한다
- **`spring.lifecycle.timeout-per-shutdown-phase` 가 드레인 상한과 같은 프로퍼티다.** 둘 다 `app.processing.timeout-seconds` 에서 나오므로 여유가 0 이다. 드레인이 상한을 꽉 채우면 스프링이 같은 순간에 그 phase 를 포기한다. 의도한 절약(두 값이 어긋날 자리를 만들지 않는다)의 대가이고, 상한을 꽉 채우는 상황 자체가 이미 비정상이라 지금은 둔다
- **타임아웃은 스텁이 스스로 지킨다.** 진짜 구현에서 같은 값이 SDK 타임아웃으로 넘어간다는 것은 step12 에서야 검증된다
- **관측 스택에 속도 제한이 없다.** step9-D 에서 접수 속도 제한을 들어냈고, 선결제 잔고를 지키는 진짜 벽은 step12 의 일일 원가 상한이다. 08 처럼 슬롯이 다 묶인 상태에서도 접수는 계속 받는다
- **hang 은 스텁 전용 손잡이다.** 진짜 Claude 가 같은 방식으로 멈출지는 step12 에서 본다. 우리가 검증한 것은 "우리 쪽이 어떻게 반응하는가"까지다

## 명령어

```
# 관측 스택 (local 프로필)
docker compose -f deploy/observability/docker-compose.yml up -d --build
./deploy/observability/scripts/seed.sh
./deploy/observability/scripts/smoke.sh

# 시나리오 전체 (8개, 40~60분)
./deploy/observability/scenarios/run-all.sh

# 08 만 — 멈춘 워커와 슬롯 누수 (10분)
./deploy/observability/scenarios/08-worker-hang.sh

# 전체 테스트와 CI 조합
./gradlew test detekt ktlintCheck

# step9 대비 이 단계가 무엇을 더했는지
git log --oneline step9-auth..step11-external
```
