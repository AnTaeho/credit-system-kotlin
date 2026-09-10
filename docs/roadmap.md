# 크레딧 시스템 개선 로드맵 — 실서비스 수준까지

> 작성일: 2026-09-10 (v2, 처음부터 다시 잡음)
> 목표: 실제로 서비스할 계획은 없지만, 실서비스에 올려도 되는 수준까지 끌어올린다.
> 보안은 의도적으로 낮은 수준만 잡는다(조직 식별용 API 키 정도). 기능·구조·배포·CI/CD에 집중한다.
> 이 문서는 살아있는 문서다. 단계가 끝날 때마다 결정 기록과 미결 사항을 갱신한다.

---

## 0. 지금 어디에 있나 — 실서비스 기준 진단

`develop`(`69a67c5`, step7 머지 후) 기준. 있는 것과 없는 것을 같은 표에 둔다.

### 이미 실서비스 수준인 것

| 영역 | 상태 |
|---|---|
| 쓰기 정합성 | 조건부 UPDATE, 멱등키, attemptNo CAS 상태 전이. 0행 분기로 통일된 방어 |
| 회수 | Redis heartbeat + updatedAt 백스톱(Redis 없이도 동작), 재시도 상한, 최종 환불 |
| 관측 | Micrometer 이벤트 분리, Prometheus·Grafana compose, 알람 12개, 장애 시나리오 9개 실측 |
| 테스트 | Testcontainers(MySQL·Redis) 위 36클래스 181개. 동시성 테스트 포함 |
| 정적 검사 | detekt, ktlint |

### 실서비스라면 막히는 것

| 영역 | 현재 | 왜 문제인가 |
|---|---|---|
| 스키마 관리 | `ddl-auto: update` | 운영 DB를 Hibernate가 마음대로 바꾼다. 원장 재설계 같은 컬럼 변경을 안전하게 못 한다 |
| 설정·비밀 | `application.yml`에 DB 비밀번호 평문, 프로파일 없음 | 로컬·테스트·운영이 같은 파일. 이미지 하나로 환경을 갈아탈 수 없다 |
| 빌드·배포 | Dockerfile이 호스트에서 만든 jar를 담음. CI 없음. 원격은 GitHub만 | 내 노트북에서만 빌드된다. 테스트가 PR에서 안 돈다 |
| 종료 | graceful shutdown 없음 | 배포할 때마다 처리 중인 job이 죽고 회수에 맡겨진다. 회수는 안전망이지 정상 경로가 아니다 |
| 다중 인스턴스 | 스케줄러 3개가 인스턴스마다 돈다 | 2대를 띄우면 회수·대사·정리가 겹친다. CAS 덕에 안전한지는 실측 전 |
| API | `POST/GET /api/jobs`, `GET/POST /api/organizations/me/*`, `GET /api/ledger`. job 단건 조회 없음. 목록은 전체 반환 | job 하나가 어떻게 됐는지 물을 수 없다. 조직이 job 10만 건이면 목록 API가 죽는다 |
| 조직 식별 | `X-Organization-Id` 헤더를 그대로 믿음 | 남의 조직 ID를 적으면 남의 크레딧을 쓴다 |
| 외부 의존 | `GenerationStubClient`가 `Thread.sleep` | 네트워크가 없으니 타임아웃·재시도·서킷브레이커가 실제로는 검증된 적이 없다 |
| 원장 | 단식. `balance` 하나, hold 즉시 차감, CONFIRM 행은 0원, 대사는 `initialBalance + Σ` | confirm 누락이 티가 안 난다. 원장이 진실이 아니라 보조 기록이다. 부분 확정 불가 |
| 로그 | 텍스트 로그, 요청 상관 ID 없음 | 한 요청이 워커·스케줄러를 거치며 남긴 로그를 묶어 볼 수 없다 |
| 문서 | README 없음. API 문서 없음 | 저장소를 연 사람이 무엇인지 모른다 |
| 알림 | Alertmanager 없음 | 알람 규칙은 있는데 아무도 못 받는다 |

---

## 1. 방향

**"제품 하나가 성장한 기록"을 만든다.** 새 프로젝트를 만들지 않는다.
각 단계는 독립적으로 배포 가능하고, 각 단계가 끝나면 포폴이 그 시점에서 완결된다.

우선순위는 의존 관계로 정한다.
1. 기반(마이그레이션·설정·CI)이 없으면 그 뒤의 어떤 변경도 안전하게 못 한다 → 가장 먼저
2. 원장 재설계는 스키마·지표·알람·시나리오·테스트로 번진다 → 배포보다 먼저. 옛 스키마를 VM에 올렸다가 다시 올리지 않기 위해
3. 실제 배포 환경이 있어야 그 위에서 API·외부 의존·배치를 실측한다
4. 대용량과 과금은 그 위에 얹는다

---

## 2. 확정된 설계 결정

### 결정 1 — Kafka를 넣지 않는다 (2026-09-10)
DB가 큐이고 상태 전이가 attemptNo CAS로 보호된다. 큐가 없어도 이중 처리가 안 난다는 게 step4~5의 논지이고,
인스턴스 2대 실험이 그 주장을 실측으로 바꾼다. Kafka 관련 질문은 티켓 예매 프로젝트에서 받는다.

### 결정 2 — 원장이 유일한 진실이다 (2026-09-10)
`initialBalance`를 없애고 초기 잔액도 CHARGE 원장 행으로 만든다. 대사 공식은 `잔액 − Σ원장`이 된다.
정산·대사 배치는 예외 없이 원장만 읽는다.

### 결정 3 — available / held 분리
```
hold:    available -= amount,  held += amount     WHERE available >= amount
confirm: held -= amount                            WHERE held >= amount
refund:  held -= amount,  available += amount      WHERE held >= amount
```
`held >= amount`는 이중 confirm을 막는 조건이 아니다(그건 attemptNo CAS가 한다).
훼손이나 버그로 held가 모자랄 때 조용히 음수가 되는 대신 0행을 돌려 방어 카운터에 잡히게 하는 조건이다.
total은 저장하지 않는다. 파생값이다.

### 결정 4 — 복식부기 원장, 부분 확정 지원
행마다 from/to 계정을 가진다. 소비 계정과 충전 원천 계정은 조직 행의 컬럼이 아니라 원장에만 존재하는 시스템 계정이다.
confirm 금액은 hold 금액보다 작을 수 있다(`hold 100 → confirm 73 + refund 27`). 정정은 역분개 행 추가.

### 결정 5 — 감시 불변식
```
① available drift = available − Σ(to=available) + Σ(from=available)      항상 0
② held drift      = held      − Σ(to=held)      + Σ(from=held)           항상 0
③ held            = Σ(HOLDING·PROCESSING·FAILED 상태 job의 미확정 금액)    상태-금액 일치
```
FAILED가 들어가는 이유: 이 코드는 FAILED 뒤에 REFUNDED가 따로 있어, FAILED인 job은 아직 held에 돈이 남아 있다.

---

## 3. 단계

### step8 — 운영 기반 (마이그레이션·설정·CI·종료)

**증명하는 것:** 이 저장소는 내 노트북 없이도 빌드·테스트·실행된다.
**규모:** 작다. 1주. 도메인 코드는 건드리지 않는다.

1. Flyway 도입. 현재 스키마를 V1 baseline으로 뜨고 `ddl-auto: validate`로 전환
2. 프로파일 분리(`local` / `test` / `prod`). 비밀은 전부 환경변수. 저장소에서 비밀번호 제거
3. 루트 `docker-compose.yml`(로컬 개발용 MySQL·Redis)과 루트 `Dockerfile`(멀티스테이지, 이미지 안에서 빌드).
   `deploy/observability`의 "호스트 빌드 jar" 방식은 이 시점에 폐기
4. GitHub Actions
   - PR: `./gradlew test detekt ktlintCheck` (Testcontainers는 Actions 러너의 Docker로 돈다)
   - `develop` 머지: 이미지 빌드 → GHCR push. 태그는 git sha
5. graceful shutdown: `server.shutdown: graceful` + 워커 executor가 진행 중인 job을 마칠 때까지 대기. 대기 상한은 `processing.timeout`에서 유도
6. README. 무엇인지, 어떻게 띄우는지, 문서 지도(`docs/step*`, `STEPS.md`)

**완료 기준:** PR에서 테스트가 초록. `docker compose up`만으로 로컬 전체가 뜬다. `SIGTERM`에 진행 중 job이 회수 없이 완료된다.

#### 완료 기록 (2026-09-10, 브랜치 `step8-ops`)

상세는 [`docs/step8-ops.md`](step8-ops.md).

| 커밋 | 내용 |
|---|---|
| `6e1fc74` | step8-A — Flyway 도입(`V1__baseline.sql`)과 `ddl-auto: validate`, 프로파일 분리, 저장소에서 평문 비밀번호 제거 |
| `b804f32` | step8-C — 루트 `Dockerfile`(멀티스테이지)·`docker-compose.yml`, GitHub Actions `ci.yml`/`image.yml` |
| `c3bdda2` | step8-D — 관측 스택의 DB 자격증명을 루트 compose 계약(`credit_system`/`credit`/`credit`)으로 통일 |
| `79616b7` | chore — 추적되던 faultpad `__pycache__` 정리 |
| `3bdbbf2` | step8-B — graceful shutdown. `WorkerDrainGate` + `GenerationWorkerLifecycle`, 드레인 이벤트·카운터 |
| `bf98d02` | step8-E — README, `docs/step8-ops.md`, STEPS.md, 로드맵 완료 기록 |
| `3b4a8ce` | test — CI 1차 실패를 보고 드레인 테스트 전제를 "PROCESSING + heartbeat LIVE" 로 |

완료 기준 대조:

| 기준 | 결과 |
|---|---|
| PR에서 테스트가 초록 | **충족.** PR #1에서 1차 실패(드레인 테스트의 전제 — 선점과 첫 heartbeat 사이 창) → 테스트 전제 수정 → 2차 성공, 2분 48초. Testcontainers·detekt toolchain 은 첫 실행부터 문제없었다. GHCR push 는 develop 머지 뒤에야 검증된다 |
| `docker compose up`만으로 로컬 전체가 뜬다 | **충족(조건부).** 기본 `up`은 MySQL·Redis만 띄우고 앱은 `--profile app`이다. 개발 중 앱 재시작 빈도를 고려해 일부러 나눴다. 조직 생성 API가 없어 첫 요청 전 SQL 삽입이 여전히 필요하다 |
| `SIGTERM`에 진행 중 job이 회수 없이 완료된다 | **충족.** `bootRun` + job 2개 + SIGTERM 실측 — 웹서버 graceful 2초 → 드레인 8.2초 → job1 `COMPLETED`, job2 `HOLDING` 유지, 회수 0회. 재현 조건은 스텁 지연 15초 고정, `concurrency: 1`, 이 머신(Apple Silicon macOS) |
| README (6번 항목) | **충족.** 루트 `README.md`. `STEPS.md`에 step7·step8 항목 추가 |

테스트는 6개 늘어 187개다(`GenerationWorkerLifecycleTest` 5, `GenerationDrainOnShutdownTest` 1).

step9로 넘기는 것:
- **`status`·`type`이 네이티브 `ENUM`이다.** `@Enumerated(STRING)`이 MySQL에서 `VARCHAR`가 아니라 `ENUM`을 만든다. 원장 재설계에서 상태나 타입을 추가하면 `ALTER TABLE ... MODIFY`가 함께 필요하다
- **H2 테스트는 마이그레이션을 안 탄다.** 마이그레이션 검증은 Testcontainers를 쓰는 테스트가 건드리는 범위까지다

step10으로 넘기는 것:
- CI 첫 실행 검증, 스케줄러 겹침 실측과 ShedLock, 드레인 상한 초과분(지금은 `outcome=abandoned` 카운터로 드러낼 뿐 회수에 맡긴다), 시크릿 관리, `latest` 태그를 무엇으로 부를지

### step9 — 원장 재설계

**증명하는 것:** 복식부기 원장으로 "돈이 어디서 어디로 갔는지"가 빠짐없이 남고, 불변식 3개가 실측으로 0이다.
**규모:** 크다. 2주. 이 프로젝트에서 가장 넓게 번지는 변경.

1. Flyway V2: `organizations`에 `available`·`held` 추가, `initialBalance` → CHARGE 행으로 이관, `balance` 제거.
   `ledger_entries`에 `from_account`·`to_account`·확정액. `jobs`에 `confirmed_amount`
2. `OrganizationRepository` hold/confirm/refund 3쿼리. 각 0행 분기가 `DefenseMetrics` point 라벨
3. `LedgerEntry` 복식 전환. CONFIRM이 실제 금액을 가진다. 부분 확정 시 CONFIRM + REFUND 2행
4. 대사 태스크: drift 공식 계정별 분리. 스냅샷 게이지: held 실측 vs Σ job 비교
5. 알람·대시보드 갱신(`CreditNegativeBalanceOrgs`는 available·held 둘 다). 시나리오 05·seed 수정
6. step7 장애 시나리오 9개 재실측. 문서 `docs/step9-ledger.md`에 "왜 바꿨나"와 새 실측
7. 정정 거래 API (역분개). 승인 주체는 지금은 운영자 헤더 하나로 단순화, 감사 로그는 원장 행 자체

**완료 기준:** 181개 테스트 통과(잔액 관련은 재작성). 시나리오 05에서 훼손이 available drift·held drift 중 어느 쪽인지 구분되어 알람이 뜬다.

### step10 — 실제 배포와 다중 인스턴스

**증명하는 것:** 인터넷에 떠 있고, 무중단으로 배포되고, 2대가 겹쳐도 이중 회수가 없다.
**규모:** 중간. 1~2주. 인프라 삽질 시간이 예측이 안 되니 넉넉히.

1. Oracle Cloud Always Free ARM VM. compose 하나로 app×2 + MySQL + Redis + Prometheus + Grafana + Alertmanager + Caddy(리버스 프록시·TLS)
2. CD: GHCR push 뒤 SSH로 `compose pull && compose up` — 앱 컨테이너를 하나씩 교체. step8의 graceful shutdown이 여기서 검증된다
3. 스케줄러 3개(회수·대사·멱등키 정리)에 ShedLock. **먼저 ShedLock 없이 2대를 띄워 겹침이 실제로 어떤 지표로 드러나는지 실측한 뒤** 넣는다
4. Alertmanager → Discord 웹훅. 알람 임계값은 설정값에서 유도(step7 방식 유지)
5. k6 baseline 트래픽을 VM 안에서 상시 유지. 장애 시나리오를 실제 VM에서 재실행
6. health 재설계: Redis down이 readiness DOWN이 되면 안 된다(백스톱이 있으니 degraded). liveness와 readiness를 분리

**완료 기준:** `develop` 머지 후 손대지 않아도 VM이 새 버전으로 바뀐다. 배포 중 k6 에러율 0. 2대에서 회수 이중 발동 0.

### step11 — API를 제품 수준으로

**증명하는 것:** 외부에서 쓸 수 있는 API이고, 외부 의존이 불안정해도 시스템이 흔들리지 않는다.
**규모:** 중간. 2주.

1. `GET /api/jobs/{id}`, jobs·ledger 커서 페이징(offset과 실측 비교는 step12로)
2. OpenAPI(springdoc). 오류 응답 규격 통일(이미 `ErrorResponse` 있음)
3. 조직 API 키. `X-Organization-Id`를 믿는 대신 API 키 → 조직 해석. 필터 하나. 이 이상의 보안은 안 한다
4. 요청 상관 ID(MDC)와 JSON 구조화 로그. job이 워커·스케줄러를 거쳐도 같은 ID로 묶인다
5. 외부 생성 API를 **네트워크 너머로** 보낸다. `GenerationClient` 인터페이스 + 스텁 HTTP 서버(별도 컨테이너, 지연·실패율을 API로 조절).
   `RestClient` 타임아웃, Resilience4j 재시도·서킷브레이커·벌크헤드. 장애 패드의 07·08이 진짜 네트워크 장애가 된다
6. OpenTelemetry 트레이스 (HTTP → 워커 → 외부 호출). 홉이 생긴 시점이라 이제 의미가 있다

**완료 기준:** 스텁 서버를 죽이면 서킷이 열리고 hold가 거절되며 held가 늘지 않는다. 트레이스 하나에 세 span이 보인다.

### step12 — 대용량과 정산

**증명하는 것:** 읽기 경로와 배치를 다룰 수 있다.
**규모:** 크다. 3주. 데이터 생성기가 그 자체로 작은 프로젝트.

1. 데이터 생성기: 수천만 원장 행. 조직별 편중(파레토), 시간 분포, 실패·환불·부분확정 비율. `LOAD DATA` 또는 배치 INSERT
2. Spring Batch 월 정산서: 청크·파티셔닝·재시작
3. 대사 배치: 원장 replay vs 잔액. 불일치 시 역분개 생성 정책
4. 원장 스냅샷(월말 잔액) — replay 비용이 실측으로 문제가 될 때 도입
5. 페이징·인덱스 실측: offset vs 커서, 실행계획, 인덱스 변경 전후. 모든 수치에 재현 조건

**완료 기준:** 정산서 샘플, 대사 리포트, 전후 비교표.

### step13 — 토큰 계량과 스트리밍 (선택)

**증명하는 것:** 비용을 모르는 채 예약하고, 알게 된 뒤 확정한다.

1. 스텁 서버가 토큰 사용량을 돌려준다. 예상치 hold → 실사용 confirm + 잔여 refund(step9의 부분 확정이 여기서 쓰인다)
2. SSE 스트리밍과 중간 실패 과금 정책
3. 이 시점에 README와 프로젝트 이름을 "AI 서비스 계량·과금·정산 플랫폼"으로 전환

---

### 결정 6 — 외부 생성 API는 선택 가능하게 (2026-09-10)
`GenerationClient` 인터페이스 아래 스텁 HTTP 서버(기본)와 실제 LLM API(프로파일 선택)를 둔다.
실제 토큰 사용량이 필요한 실험(step13)에서는 비용을 감수하고 실제 API를 쓴다.

### 결정 7 — 무중단 배포를 한다 (2026-09-10)
app×2 + Caddy 순차 교체. graceful shutdown(step8)과 ShedLock(step10)이 여기서 검증된다.

## 4. 미결 사항

- [ ] 정정 거래의 승인 주체 — 운영자 헤더로 충분한가 (step9)
- [ ] ShedLock vs DB 리더 선출 직접 구현 — 학습 가치와 시간의 교환 (step10)
- [ ] 기존 `docs/step2~7`의 "hold 즉시 차감" 서술: 그대로 두고 step9 문서에 "왜 바꿨나"를 쓴다. 포폴 3~7페이지는 step9 뒤에 고친다
- [ ] 취업 준비 잔여 기간 → 단계별 범위 조절 기준

---

## 5. 면접 관통 질문

- **왜 큐를 안 썼나** — DB가 큐, CAS가 보호. step10의 2대 실측이 근거
- **2대에서 스케줄러가 겹치면** — step10에서 겹치게 두고 실측한 뒤 ShedLock
- **배포 중 처리 중이던 job은** — graceful shutdown(step8)이 정상 경로, 회수(step5)는 안전망
- **조건부 UPDATE의 한계** — 조직당 한 행이라 hot row. step12의 부하 실측에서 row lock 대기가 보이면 버킷 분할 검토
- **0행이 잔액 부족인지 경합 패배인지** — 지금은 구분 안 함. 재조회로 구분하는 비용 vs 이득
- **다른 조직 크레딧을 못 건드리게** — step11 API 키. 그 이상은 의도적으로 범위 밖
- **성능 수치 재현 조건** — 모든 수치에 하드웨어·인스턴스 수·데이터 규모·풀 크기·도구 명시

---

## 6. 원칙

- 관측·정산·과금·배포는 전부 크레딧 시스템 안에 편입한다
- 모든 수치에 재현 조건. 한계는 면접관보다 먼저 밝힌다
- 도메인 개념 하나당 설계 변경 하나
- 단계 종료마다 포폴 갱신 + 지원 병행. 3단계 뒤에 지원하겠다는 생각은 위험하다
- 판단 지점은 임의 결정하지 않고 기록한다
- 리팩터링은 코드가 덜 알게 만드는 것. 파일·패키지 수는 근거가 아니다

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-09-10 | v1 작성(3단계 마일스톤). 코드 대조로 Kafka 부재·step7 중복 확인 |
| 2026-09-10 | v2로 전면 재작성. 목표를 "실서비스 수준"으로 바꾸고 step8~13 여섯 단계로 재편. Kafka 제외·원장 유일 진실 확정 |
| 2026-09-10 | 결정 6·7 확정(외부 생성 선택 가능, 무중단 배포). step8 착수 — 브랜치 `step8-ops` |
| 2026-09-10 | step8 완료 기록 추가. 완료 기준 4개 중 3개 충족, "PR에서 테스트가 초록"은 push 전이라 미검증. `docs/step8-ops.md`·`README.md` 작성, `STEPS.md`에 step7·step8 항목 추가 |
| 2026-09-10 | push·PR #1. CI 1차 실패(테스트 전제) → 2차 성공 2분 48초. 완료 기준 4개 전부 충족(GHCR 은 머지 후). |
