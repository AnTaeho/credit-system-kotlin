# 부하 측정 실행 절차 (Phase 3-C · 3-D)

요구서 `docs/01-requirements.md` 의 **PERF-01~07** 을 실제로 재는 절차다.
스크립트는 판정을 사람에게 맡기지 않는다 — 목표치는 전부 k6 `thresholds` 에 박혀 있고,
런이 무효가 되는 조건(401, 시드 부족, 발생기 병목)도 threshold 로 걸려 있다.

```
load/
  seed/grant-credits.sh     크레딧 시드(3-A)
  seed/seed-ledger.sh       원장 대용량 시드(3-A, PERF-04 용)
  k6/lib/common.js          헤더·idemKey·응답 분류·재시도·공용 메트릭·요약
  k6/00-generator-ceiling.js  부하 발생기 천장 측정 (04 의 선행)
  k6/01-tier-a.js           정상 부하        PERF-01 · 02 · 07
  k6/02-hot-account.js      핫 계정 편중     PERF-05
  k6/03-external-delay.js   외부 지연 주입   PERF-03
  k6/04-survival-spike.js   생존 스파이크    PERF-06
  results/TEMPLATE.md       결과 파일 템플릿
```

k6 는 `/opt/homebrew/bin/k6` (v1.6.0).

---

## 0. 먼저 읽을 것 — 함정 목록

측정을 조용히 망가뜨리는 것들이다. 순서대로 확인하고 시작해라.

### (1) 포트 3306·6379 는 이미 둘씩 잡혀 있다

이 머신에는 homebrew 의 `mysqld` 와 `redis-server` 가 **IPv4 127.0.0.1** 에,
docker compose 의 컨테이너가 **IPv6 \*(all)** 에 각각 같은 포트를 잡고 있다.
확인:

```bash
lsof -nP -iTCP:3306 -sTCP:LISTEN
lsof -nP -iTCP:6379 -sTCP:LISTEN
```

`localhost` 는 보통 IPv4 로 먼저 풀리므로, 기본 설정(`jdbc:mysql://localhost:3306`)의 앱은
**compose 의 MySQL 이 아니라 homebrew 의 mysqld** 로 붙는다. `credit`/`credit` 계정이 없으면
`Access denied` 로 죽고, 있으면 더 나쁘다 — **엉뚱한 DB 를 재고 `postLoadCheck` 는 빈 DB 를 본다.**

두 방법 중 하나를 골라라. 고른 쪽을 결과 파일에 적어라.

```bash
# (a) homebrew 쪽을 내린다 — 권장. docker stats 로 DB 부하를 보려면 이쪽이어야 한다.
brew services stop mysql
brew services stop redis

# (b) 앱을 IPv6 로 붙인다.
export SPRING_DATASOURCE_URL='jdbc:mysql://[::1]:3306/credit_system'
export SPRING_DATA_REDIS_HOST='::1'
```

정말 compose 쪽에 붙었는지 확인:

```bash
docker compose exec -T mysql mysql -N -ucredit -pcredit credit_system \
  -e "SELECT COUNT(*) FROM ledger_entries"   # 앱이 쓴 뒤 이 값이 늘어야 한다
```

### (2) 정체 모를 컨테이너가 `docker stats` 를 오염시킨다

`docker ps` 에 Testcontainers 풍의 랜덤 이름(`eager_mcnulty`, `lucid_snyder` 등)이 3306/6379 를
다른 포트로 publish 한 채 떠 있을 수 있다. **지우지 마라** — 같은 시각 돌고 있는 테스트의 것일 수 있다.
대신 `docker stats` 를 읽을 때 `credit-system-kotlin-mysql-1` 과 `-redis-1` 만 보고,
그 외 컨테이너가 떠 있었다는 사실을 결과 파일에 적어라(CPU 를 나눠 쓴다).

### (3) 스텁 프로파일 환경변수는 대시가 빠진다

**브리프에 적힌 `APP_STUB_MIN_DELAY_MILLIS` 는 틀린 이름이다.** Spring 의 relaxed binding 은
대시를 지우고 점을 밑줄로 바꾼다. `app.stub.min-delay-millis` 의 환경변수 형태는:

| 프로퍼티 | 환경변수 |
|---|---|
| `app.stub.min-delay-millis` | `APP_STUB_MINDELAYMILLIS` |
| `app.stub.max-delay-millis` | `APP_STUB_MAXDELAYMILLIS` |
| `app.stub.failure-rate` | `APP_STUB_FAILURERATE` |
| `app.auth.allowed-emails` | `APP_AUTH_ALLOWEDEMAILS` (쉼표 구분) |
| `app.auth.admin-emails` | `APP_AUTH_ADMINEMAILS` |

`APP_STUB_MIN_DELAY_MILLIS` 를 주면 `app.stub.min.delay.millis` 라는 **다른 키**가 되어
**아무 일도 일어나지 않는다.** 01 과 03 이 둘 다 3~7초에서 돌고, PERF-03 의 비교는 무의미해진다.
(`application.yml` 주석이 같은 규칙을 `APP_AUTH_ALLOWEDEMAILS` 로 이미 적어 뒀다.)

`/actuator/env` 는 노출돼 있지 않으므로(`management.endpoints.web.exposure.include: health,info,prometheus`)
설정이 먹었는지는 **동작으로** 확인한다 — 0-6 의 확인 절차를 보라.

### (4) Hikari 기본값 10 을 바꾸지 마라

`spring.datasource.hikari.*` 를 손대면 "현재 구현"의 측정이 아니다(요구서 합의 4).
PERF-06 의 예상 경로가 바로 이 커넥션 고갈이다. 고갈되는 것을 보는 것이 목적이다.

### (5) 계정이 둘밖에 없다

`application-local.yml` 의 허용 목록은 `dev@local.test`, `admin@local.test` 둘뿐이다.
계정을 늘리려면 환경변수로 **목록 전체를 덮어쓴다.** `admin@local.test` 를 빼면
`AuthProperties.init` 의 `require` 에 걸려 **기동이 거부된다**(운영자는 허용 목록의 부분집합이어야 한다).

### (6) 409 두 종류를 갈라 센다 / 401 은 결과가 아니라 사고다

- `DUPLICATE_IN_PROGRESS` → 성공. 멱등이 동작한 것이다.
- `INSUFFICIENT_BALANCE` → **시드 실패.** `insufficient_balance` 카운터에 threshold `count==0` 이 걸려 있어
  한 건이라도 나오면 런이 실패로 끝난다. 다시 시드하고 다시 돌려라.
- 401 → 허용 목록이나 개발 로그인 설정이 안 먹은 것이다. 역시 `count==0` 이다.

각 스크립트의 `setup()` 이 시작 전에 계정별 잔액을 읽어 필요량보다 적으면 **런을 시작하지 않는다.**

### (7) 발생기의 한계를 시스템의 한계로 적지 마라

k6 가 목표 RPS 를 못 내면 `dropped_iterations` 가 오르고 "insufficient VUs" 경고가 뜬다.
01~03 은 `dropped_iterations: ['count==0']` 로 걸어 그런 런을 **무효**로 만든다.
(10분에 한 건만 흘려도 실패다. 엄격한 것은 의도지만, 실제로 걸리면 `rate<0.001` 로 낮추고
낮췄다는 사실을 결과 파일에 적어라.)
04 는 일부러 안 건다(3,000 RPS 는 발생기가 못 낼 수 있다) — 대신 수치를 결과 파일에 적는다.
필요한 VU 수 ≈ 초당 요청 × 응답 지연(초)다. 응답이 1초로 늘면 3,000 RPS 는 VU 3,000 개를 요구한다.

```bash
ulimit -n          # 충분히 큰지(수십만) 확인. 작으면 `ulimit -n 65536`
```

---

## 0-B. 환경 고정 (2026-09-23 추가)

측정이 재현되려면 각 층이 쓸 수 있는 자원이 정해져 있어야 한다. 제한이 없으면 두 실행의
차이가 시스템 변화인지 자원 배분 변화인지 가를 수 없다. 그래서 세 층을 못 박았다.

| 층 | 고정값 | 고정하는 곳 |
|---|---|---|
| Docker VM | 8 vCPU / 4 GiB | Docker Desktop 설정(이 저장소 밖). **결과 파일에 적는다** |
| MySQL 컨테이너 | CPU 4 / 메모리 3 GiB, buffer pool 128 MiB, max_connections 200, performance_schema ON | `load/docker-compose.load.yml` |
| Redis 컨테이너 | CPU 1 / 메모리 512 MiB | 같은 파일 |
| 앱 JVM | 힙 2 GiB 고정(`-Xms=-Xmx`), `AlwaysPreTouch`, `ActiveProcessorCount=4`, G1, GC 로그 | `load/run-app.sh` |
| 커넥션 풀 | **건드리지 않는다** — Hikari 기본 최대 10 이 현재 구현이다 | — |

실행:

```bash
# 인프라 (오버레이를 얹는다. 개발용 compose 는 그대로 둔다)
docker compose -f docker-compose.yml -f load/docker-compose.load.yml up -d mysql redis

# 적용 확인
docker inspect credit-system-kotlin-mysql-1 --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}'
docker exec credit-system-kotlin-mysql-1 mysql -uroot -proot -N \
  -e "SELECT @@max_connections, @@innodb_buffer_pool_size, @@performance_schema"

# 앱 (프로파일 b: 지연 50~100ms·실패율 0 / a: application.yml 기본값)
load/run-app.sh b
```

**세 가지를 일부러 바꾸지 않았다.**

- `innodb_buffer_pool_size` 는 이미지 기본값 128 MiB 를 그대로 다시 적었다. 올리면 PERF-04 가
  좋아지지만 그때 재는 것은 "현재 구현"이 아니라 "튜닝한 구현"이다. 기준선을 먼저 얻는다.
- Hikari 최대 10 도 그대로다. 같은 이유다.
- Docker VM 은 8 vCPU / 4 GiB 인 현재 값을 유지한다. 4 GiB 라 1억 행 원장에서는 페이지 캐시가
  모자라 **PERF-04 가 IO 바운드로 나올 것이 예상된다** — 그것도 측정 결과이므로 그대로 적는다.

**합이 호스트를 넘는다**(VM 8 + 앱 4 + k6). 일부러 그렇게 둔다. 여기서 정하는 것은 독점이
아니라 상한이고, 앱과 DB 가 CPU 를 두고 경쟁하는 것은 인벤토리 5절이 기록한 이 환경의 성질이다.
없애려면 기계가 두 대여야 한다.

## 1. 준비

### 1-1. 인프라

```bash
cd /Users/antaeho/workspace/projects/credit-system-kotlin
docker compose up -d
docker compose ps
```

### 1-2. 계정 목록을 정하고 앱을 띄운다

앱은 **호스트 JVM** 으로 띄운다(요구서 합의 4 — 컨테이너 런타임에서는 재지 않는다).

```bash
export LOAD_USERS='load01@local.test,load02@local.test,load03@local.test,load04@local.test,load05@local.test'
export APP_AUTH_ALLOWEDEMAILS="admin@local.test,dev@local.test,$LOAD_USERS"
export APP_AUTH_ADMINEMAILS='admin@local.test'

# 프로파일 (b) — 지연을 낮춘 스텁. 01 · 02 · 04 가 쓴다. **2026-09-23 확정값.**
# 지연 50~100ms: 워커 처리량이 3/0.05~0.1 = 초당 30~60건이 되어, 기본값(초당 0.43~1.0건)보다
#   두 자릿수 위다. 접수율 500 RPS 는 여전히 못 따라가지만, 이 프로파일의 목적은 워커를
#   따라잡게 하는 것이 아니라 **접수 경로(TX-1)와 DB 가 병목인지 보는 것**이다.
# 실패율 0: 재시도가 돌면 같은 job 이 외부 호출을 여러 번 쓰고 HOLDING 이 늘었다 줄었다 해서
#   PERF-01(접수 지연)과 PERF-07(적체 곡선)이 둘 다 흐려진다. 실패 경로는 03·04 와
#   불변식 테스트가 따로 본다.
export APP_STUB_MINDELAYMILLIS=50
export APP_STUB_MAXDELAYMILLIS=100
export APP_STUB_FAILURERATE=0

SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

03 은 같은 앱을 **환경변수 없이**(= `application.yml` 기본값 3000/7000/0.3) 다시 띄워서 돌린다.
**01 과 03 의 차이는 이 세 변수뿐이어야 한다.** 다른 것을 함께 바꾸면 PERF-03 을 판정할 수 없다.

### 1-3. 사용자 행을 미리 만든다

첫 로그인 INSERT 가 측정에 섞이지 않도록, 부하 전에 계정마다 한 번씩 친다.

```bash
for u in ${LOAD_USERS//,/ } admin@local.test; do
  echo -n "$u -> "; curl -s -H "X-Dev-User: $u" localhost:8080/api/users/me/balance; echo
done

docker compose exec -T mysql mysql -N -ucredit -pcredit credit_system \
  -e "SELECT id, email, balance FROM users ORDER BY id"
```

### 1-4. 크레딧 시드

요구서 1-5 의 산술이다. 1회 지급 상한이 100만이라 호출 횟수가 곧 총액 ÷ 100만이다.

**크레딧은 쓰면 없어진다. 런마다 다시 시드해라.** 01 을 돌린 뒤 03 을 그대로 돌리면 잔액이
남아 있지 않다 — `setup()` 의 선행 검사가 막아 주지만, 막히고 나서 시드하면 그만큼 늦는다.

| 시나리오 | 총 필요 크레딧 | 산술 |
|---|---|---|
| 01 · 03 (Tier A 10분) | 30,000,000 | 500 × 600 × 100 |
| 02 (핫 계정 10분) | 30,000,000 (그중 **핫 계정 1,500,000**) | 300,000 건 중 1/20 이 핫 |
| 04 (스파이크) | 약 114,000,000 | `ESTIMATED_WRITES` 1,140,000 × 100 |

**계정 5개 기준, 01·02·03 은 계정당 8,000,000(= 8회 호출)을 넣는다.**

```bash
for id in 2 3 4 5 6; do            # 1-3 의 SELECT 로 확인한 실제 id 를 써라
  load/seed/grant-credits.sh "$id" 8000000 1000000
done
```

600만이 아니라 800만인 이유:
- 01·03 의 계정당 정확한 소요는 6,000,000(30,000,000 ÷ 5)이다. **딱 맞으면 여유가 0 이다** —
  1-6 의 프로파일 확인 프로브가 쓰는 1,000 크레딧 하나에도 선행 검사가 걸린다.
- 02 는 **고르게 나뉘지 않는다.** 핫 계정이 15,000 건(1,500,000), 나머지 4개가 285,000 건을
  나눠 71,250 건씩(**7,125,000**) 쓴다. 600만으로는 균등 쪽이 모자라 부하 중반에
  409 `INSUFFICIENT_BALANCE` 가 쏟아진다. 02 의 `setup()` 은 이 불균등을 그대로 계산해
  계정별로 검사하므로 모자라면 **시작 전에** 멈춘다.

**04 는 자릿수가 다르다.** 계정당 약 23,000,000(= 23회 호출):

```bash
for id in 2 3 4 5 6; do
  load/seed/grant-credits.sh "$id" 23000000 1000000
done
```

지급 호출이 115회다. 시간이 걸리니 04 를 돌리기로 결정한 뒤에 시작해라.
스파이크 길이를 줄였다면(`PEAK_HOLD` 등) 필요량도 줄어든다 — 스크립트가 시작할 때
`예상 쓰기 N 건` 을 로그로 찍으니 그 숫자 × 100 이 실제 필요량이다.

### 1-5. 실행 전 스냅샷

결과 파일에 그대로 붙일 것들이다.

```bash
docker stats --no-stream
docker compose exec -T mysql mysql -N -ucredit -pcredit \
  -e "SELECT @@max_connections, @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@innodb_buffer_pool_size"
java -version 2>&1
./gradlew --version | head -20
docker compose exec -T mysql mysql -N -ucredit -pcredit \
  -e "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'"      # 부하 후 다시 찍어 델타를 본다
```

### 1-6. 스텁 프로파일이 먹었는지 확인

`/actuator/env` 가 없으므로 **동작으로** 가른다. 잡 10건을 넣고 적체가 어떻게 빠지는지 본다.

```bash
for i in $(seq 1 10); do
  curl -s -X POST localhost:8080/api/jobs -H 'Content-Type: application/json' \
    -H "X-Dev-User: load01@local.test" \
    -d "{\"idemKey\":\"probe-$(date +%s)-$i\",\"prompt\":\"probe\"}" > /dev/null
done
for i in $(seq 1 12); do
  curl -s localhost:8081/actuator/prometheus | grep '^credit_hold_outstanding_count'
  sleep 5
done
```

- 프로파일 (b)(지연 50~100ms): 15~30초 안에 0 으로 떨어진다.
- 프로파일 (a)(지연 3~7초, 실패율 0.3): 동시 3 이라 분 단위로 천천히 빠진다.

떨어지는 속도가 기대와 다르면 환경변수 이름을 다시 봐라(함정 3).

---

## 2. 실행

모든 스크립트는 다음 환경변수를 읽는다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 애플리케이션 포트 |
| `MGMT_URL` | `http://localhost:8081` | 관리 포트(00 이 쓴다) |
| `USERS` | `dev@local.test` | 쉼표로 나눈 계정 목록 |
| `HOT_USER` | `USERS[0]` | 02 의 핫 계정 |
| `WRITE_RATE` / `READ_RATE` | 500 / 1500 | 접수·조회 RPS |
| `DURATION` | `10m` | 01·02·03 의 길이 |
| `MAX_RETRIES` | 3 | 5xx·status 0 재시도 횟수 |
| `RUN_ID` | 자동 | idemKey 접두사. **런마다 달라야 한다** |
| `SKIP_PRECHECK` | (없음) | `1` 이면 잔액 선행 검사 생략. **문법 확인 전용** |

```bash
export K6=/opt/homebrew/bin/k6
export USERS="$LOAD_USERS"
export RUN_ID="$(date +%Y%m%d-%H%M)"   # 결과 파일에 적을 수 있게 고정한다
```

`RUN_ID` 를 안 주면 VU 마다 다른 기본값이 생긴다(init 컨텍스트가 VU 별로 실행된다).
idemKey 의 유일성은 UUID 가 보장하므로 문제는 없지만, **결과 파일에 런을 가리킬 값이 없어진다.**


### 01 — Tier A 정상 부하 (PERF-01 · 02 · 07) · 프로파일 (b)

```bash
$K6 run load/k6/01-tier-a.js | tee /tmp/k6-01.txt
```

부하와 **동시에** 다른 터미널에서 게이지를 긁는다(4절).

### 03 — 외부 지연 주입 (PERF-03) · 프로파일 (a)

앱을 스텁 환경변수 없이 다시 띄운 뒤:

```bash
$K6 run load/k6/03-external-delay.js | tee /tmp/k6-03.txt
```

판정은 threshold 통과만으로 끝나지 않는다. **01 과 03 의 `{name:hold}` p50·p99 를 나란히 적어라.**

### 02 — 핫 계정 편중 (PERF-05) · 프로파일 (b)

```bash
$K6 run load/k6/02-hot-account.js -e HOT_USER=load01@local.test | tee /tmp/k6-02.txt
```

요약 끝의 `[PERF-05 판정]` 줄이 판정이다(핫 p99 ÷ 균등 p99 < 3).
**이 줄은 k6 종료 코드에 반영되지 않는다** — k6 는 메트릭 둘을 서로 비교하지 못한다.
대신 정적 방어선(hot p99 < 300ms)은 threshold 로 걸려 있다.
부하 중·후에 MySQL 락 대기를 같이 찍어라(5절).

### 04 — 생존 스파이크 (PERF-06) · 프로파일 (b)

**먼저 발생기 천장을 잰다.**

```bash
$K6 run load/k6/00-generator-ceiling.js -e TARGET_RATE=3000 -e CEILING_DURATION=30s | tee /tmp/k6-00.txt
```

`FAIL` 이면 3,000 RPS 의 결과는 시스템이 아니라 발생기의 것이 섞인다. 그 사실을 결과 파일에 먼저 적는다.

```bash
$K6 run load/k6/04-survival-spike.js | tee /tmp/k6-04.txt
```

부하 모델: 500 → 3,000 RPS 로 2분 램프 → 3,000 RPS 5분 유지 → 1분 하강 →
8~12분 **배수 구간**(50 RPS, 관찰만) → 12~13분 **판정 구간**(50 RPS, `phase:recovered`).
PERF-01 의 목표치는 **판정 구간에만** 걸린다. 5분을 통째로 평균 내면 앞부분의 배수가
뒤를 끌어내려, 2분 만에 회복해도 FAIL 이 뜬다. "5분 내 정상 복귀"는 t+5 시점의 상태다.

**3,000 RPS 가 실제로 나갈 것이라고 기대하지 마라.** 이 구성에서 볼 것은 대체로 이런 모양이다.

| 보이는 것 | 무엇인가 |
|---|---|
| 빠른 `status 0` | Tomcat 의 accept 큐가 거절한 것(기본 maxThreads 200, acceptCount 100) |
| 30초쯤 뒤의 5xx | Hikari 커넥션 획득 타임아웃(기본 30초). **이 30초도 "현재 구현"이다 — 돌리지 마라** |
| `dropped_iterations` 폭증 | 멈춘 요청 하나가 VU 하나를 30초 물고 있어서다. VU 4,000 개로는 초당 130건밖에 못 낸다 |

그래서 04 에서 읽을 값은 "몇 RPS 를 냈나"가 아니라 이 셋이다.

1. `spike_accepted` — 어떤 식으로든 접수가 받아들여진 건수(생존의 최소 증거)
2. `hikaricp_connections_pending` — 예상 병목이 실제로 거기였는지(4-3)
3. `phase:recovered` 구간의 threshold — 돌아왔는지

### 실행 후 — 매번

```bash
./gradlew postLoadCheck 2>&1 | tee /tmp/postload.txt
```

INV-02 의 세 검사와 INV-01 이 돌아간다. **출력 전체를 결과 파일에 붙인다.**
이 태스크는 compose 로 띄운 `localhost:3306/credit_system` 을 본다 — 함정 (1) 에서 IPv6 로 우회했다면
앱이 쓴 DB 와 이 태스크가 읽는 DB 가 같은지 먼저 확인해라.

---

## 3. PERF-07 — 끝까지 기다리지 마라

요구서 3-2 의 산술: 10분 접수 30만 건, 워커 처리 상한 0.43~1.0 건/s → **소진에 83~194시간**.
소진을 기다리는 것은 측정이 아니라 낭비다. 절차는 이렇다.

1. 01 을 도는 동안 15초 간격으로 게이지를 기록한다(4절 루프).
2. 부하가 끝나면 **같은 루프를 15분 더** 돌린다.
3. 15분 구간의 `credit_hold_outstanding_count` 기울기를 낸다(첫 값, 끝 값, 분당 감소량).
4. 결과 파일에는 이렇게 적는다:
   **"단조 감소 확인(분당 −N 건, 15분 관측). 완전 소진은 미측정 — 산술상 83~194시간."**

(i) 부하 중 불변식 위반 0건, (ii) 부하 제거 후 단조 감소, (iii) 게이지로 관측됨 —
세 가지가 PERF-07 의 판정이고, 세 번째는 이 루프의 출력 자체다.

---

## 4. 관측 값을 어디서 읽나

관리 포트 8081 의 `/actuator/prometheus` 다. 관리 포트가 따로일 때 health·prometheus 는
인증 없이 열린다(`SecurityConfig`). 애플리케이션 포트 8080 에는 액추에이터가 없다.

### 4-1. 스크레이프 루프 (부하 중 + 부하 후 15분)

```bash
while true; do
  echo "--- $(date +%H:%M:%S)"
  curl -s localhost:8081/actuator/prometheus \
    | grep -E '^credit_(hold|job|invariant|snapshot|generation)' \
    | grep -v '^#'
  sleep 15
done | tee /tmp/gauges.txt
```

### 4-2. 메트릭 이름

소스의 이름은 점 표기(`credit.hold.outstanding.count`)이고, Prometheus 로 나갈 때 Micrometer 가
점을 밑줄로 바꾸고 **카운터에 `_total`**, **baseUnit 이 있는 게이지에 `_seconds`** 를 붙인다.
아래는 그 변환 규칙으로 유도한 이름이다 — **기동 후 `curl` 한 번으로 확정하고 결과 파일에 실제 이름을 적어라.**

| 소스 (`DomainSnapshotMetrics.kt`) | 예상 노출 이름 | 뜻 |
|---|---|---|
| `credit.hold.outstanding.count` | `credit_hold_outstanding_count` | 미결 job 수 — **PERF-07 의 적체** |
| `credit.hold.outstanding.amount` | `credit_hold_outstanding_amount` | 미결에 묶인 크레딧 |
| `credit.job.oldest.pending.age` | `credit_job_oldest_pending_age_seconds` | 가장 오래된 미결의 나이 — 멈춤 탐지 |
| `credit.invariant.negative.balance.orgs` | `credit_invariant_negative_balance_orgs` | **INV-01.** 0 이 아니면 사고 |
| `credit.invariant.jobs.without.hold` | `credit_invariant_jobs_without_hold` | **INV-02 (b)** |
| `credit.invariant.unsettled.terminal.jobs` | `credit_invariant_unsettled_terminal_jobs` | **INV-02 (c)** |
| `credit.snapshot.cycles` | `credit_snapshot_cycles_total` | 스냅샷 주기 수 |
| `credit.snapshot.duration` | `credit_snapshot_duration_seconds_{count,sum,max}` | 스냅샷 한 주기 시간 |
| `credit.snapshot.staleness` | `credit_snapshot_staleness_seconds` | 스냅샷이 멈췄는지. `-1` 은 "아직 한 번도 안 찍힘" |

`ExternalCallMetrics.kt` (3-A 추가, INV-04b):

| 소스 | 예상 노출 이름 |
|---|---|
| `credit.generation.external.calls` | `credit_generation_external_calls_total` |
| `credit.generation.external.duplicate.calls` | `credit_generation_external_duplicate_calls_total` |

둘 다 태그가 없다. `duplicate / calls` 비율이 INV-04b 의 지표이고, 그 상한을 이 측정이 정한다.

스냅샷은 15초마다 갱신된다(`app.scheduling.snapshot-interval-millis: 15000`).
`credit_snapshot_staleness_seconds` 가 30 을 넘으면 **스냅샷 자체가 멈춘 것**이고,
그때의 다른 게이지 값은 낡은 값이다.

### 4-3. Hikari 지표 — 클래스패스에는 있다

요구서 2-2 가 "노출되는지 미확인"으로 남긴 항목이다. 확인한 것과 못 한 것을 갈라 적는다.

- **확인됨:** `spring-boot-jdbc-4.1.0.jar` 안에
  `org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration$HikariDataSourceMetricsConfiguration`
  이 있다. 액추에이터 + Micrometer + HikariDataSource 조합이므로 자동 설정이 붙을 조건은 갖춰져 있다.
- **미확인:** 실제로 스크레이프에 나오는지. 앱을 띄우지 않고는 확정할 수 없다.

기동 후 **반드시** 한 번 확인하고 결과 파일에 적어라.

```bash
curl -s localhost:8081/actuator/prometheus | grep -E '^hikaricp_' | grep -v '^#'
```

나오면 부하 중 이 셋을 기록한다(2-2 의 "Hikari 커넥션 대기" 칸이 이것이다).

| 이름 | 뜻 |
|---|---|
| `hikaricp_connections_active` | 사용 중인 커넥션. 기본 최대가 10 이므로 10 에 붙으면 포화다 |
| `hikaricp_connections_pending` | 커넥션을 **기다리는 스레드 수**. PERF-06 의 예상 병목이 여기서 보인다 |
| `hikaricp_connections_acquire_seconds_{count,sum,max}` | 커넥션 획득 대기 시간 |
| `hikaricp_connections_timeout_total` | 획득 타임아웃 누적 |

안 나오면 결과 파일에 **"미노출"** 이라고 적고, 대신 앱 로그의 `HikariPool-1 - Connection is not available`
예외 건수를 센다. 노출을 고치는 것은 이 단계의 일이 아니다(요구서 부록 B).

---

## 5. MySQL 락 대기 — 02 에 필수

PERF-05 의 원인이 잔액 조건부 UPDATE 의 직렬화인지 가르는 값이다. 부하 **전후** 델타를 낸다.

```bash
docker compose exec -T mysql mysql -ucredit -pcredit \
  -e "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'"
```

부하 **중**에 실제로 기다리고 있는 트랜잭션:

```bash
docker compose exec -T mysql mysql -ucredit -pcredit \
  -e "SELECT * FROM sys.innodb_lock_waits\G" 2>/dev/null
docker compose exec -T mysql mysql -ucredit -pcredit \
  -e "SHOW ENGINE INNODB STATUS\G" | sed -n '/TRANSACTIONS/,/FILE I\/O/p'
```

핫 행 락 점유 예산은 **40ms/건**(1,000ms ÷ 25)이다. `Innodb_row_lock_time_avg` 가 그 근처면
직렬화가 곧 병목이라는 뜻이다.

---

## 6. 결과 파일

`load/results/YYYY-MM-DD-<시나리오>.md` 로 만든다. 템플릿은 `load/results/TEMPLATE.md` 다.

```bash
cp load/results/TEMPLATE.md load/results/$(date +%F)-01-tier-a.md
```

템플릿의 필수 6칸(요구서 2-2)을 비워 두지 마라. 특히 마지막 칸 —
**"무엇이 먼저 포화됐는가"** 한 줄이 Tier B 외삽의 **유일한** 근거다.
모르면 "모른다"고 적어라. 빈칸은 안 된다.
