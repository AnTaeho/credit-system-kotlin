# step8-ops — 운영 기반: 마이그레이션·설정·이미지·CI·종료

step7 까지의 저장소는 **내 노트북에서만** 살아 있었다. 스키마는 Hibernate 가 부팅할 때마다 알아서 맞췄고, DB 비밀번호는 `application.yml` 에 평문으로 박혀 있었고, 이미지는 "먼저 `./gradlew bootJar` 를 돌려라"가 전제였고, 배포는 곧 진행 중인 job 을 죽이는 일이었다. step8 은 도메인 코드를 거의 건드리지 않는다. 대신 **이 저장소를 다른 기계가 빌드하고 실행할 수 있게** 만든다.

- 이전 단계: `step7-observability`
- 로드맵: [`docs/roadmap.md`](roadmap.md) 의 step8

## 이전 단계의 문제

로드맵 0절 "실서비스라면 막히는 것" 표에서 step8 이 푸는 행은 다섯이다.

| 영역 | 그때 | 왜 문제인가 |
|---|---|---|
| 스키마 관리 | `ddl-auto: update` | 운영 DB 를 Hibernate 가 마음대로 바꾼다. 원장 재설계(step9) 같은 컬럼 변경을 안전하게 못 한다 |
| 설정·비밀 | `application.yml` 에 DB 비밀번호 평문, 프로파일 없음 | 로컬·테스트·운영이 같은 파일이다. 이미지 하나로 환경을 갈아탈 수 없다 |
| 빌드·배포 | Dockerfile 이 호스트에서 만든 jar 를 담는다. CI 없음 | 내 노트북에서만 빌드된다. 테스트가 PR 에서 안 돈다 |
| 종료 | graceful shutdown 없음 | 배포할 때마다 진행 중인 job 이 죽고 회수에 맡겨진다. **회수는 안전망이지 정상 경로가 아니다** |
| 문서 | README 없음 | 저장소를 연 사람이 이게 무엇인지 모른다 |

`ddl-auto: update` 는 특히 조용한 문제였다. 잘 도는 동안에는 아무 표시가 없다가, 컬럼을 지우거나 타입을 좁히는 변경(=step9 가 하려는 것)에서만 갑자기 못 하겠다고 말한다. 그때는 이미 운영 DB 에 데이터가 있다.

종료 쪽도 같은 성격이다. step5 의 회수 장치가 있으니 배포로 죽은 job 도 결국은 재시도되거나 환불된다 — 그래서 "동작한다". 다만 배포 한 번마다 회수가 도는 것은, 예고된 종료의 대가를 예고 없는 종료의 안전망에 떠넘기는 것이다. 대가는 재시도 한 번(스텁 재호출), 회수 지연(최대 `processing.timeout-seconds`), 그리고 step10 에서 인스턴스가 둘이 되면 **살아 있는 job 을 다른 인스턴스가 뺏어 가는 것**이다.

## 무엇이 새로 생겼나

`git diff --stat develop step8-ops`(develop 은 step7 을 머지한 지점이다) 기준으로, 커밋 순서가 아니라 주제별로.

**마이그레이션과 설정 (`6e1fc74`)**

- `src/main/resources/db/migration/V1__baseline.sql` (신규) — 지금 스키마를 그대로 옮긴 baseline
- `application.yml` — `ddl-auto: update` → `validate`, `spring.flyway.enabled: true`, 평문 비밀번호와 `root` 제거(로컬 기본값 `credit_system` / `credit` / `credit` 만 남는다)
- `application-prod.yml` (신규) — 기본값 없는 환경변수 플레이스홀더
- `src/test/resources/application-test.yml` — H2 테스트는 `flyway.enabled: false` + `create-drop` 유지
- `SharedContainers.kt` — Testcontainers 테스트는 `flyway.enabled: true` + `ddl-auto: validate`
- `build.gradle.kts` — `spring-boot-starter-flyway`, `flyway-mysql`

**이미지와 CI (`b804f32`, `c3bdda2`)**

- `Dockerfile` (신규, 루트) — 멀티스테이지. 빌드가 이미지 안에서 돈다
- `.dockerignore` (신규)
- `docker-compose.yml` (신규, 루트) — 로컬 인프라(MySQL·Redis), 앱은 `--profile app`
- `.github/workflows/ci.yml` (신규) — PR 검증(`test detekt ktlintCheck`)
- `.github/workflows/image.yml` (신규) — `develop` push·`v*` 태그 → GHCR
- `deploy/observability/Dockerfile` (삭제) — 관측 스택도 루트 Dockerfile 을 본다
- `deploy/observability/docker-compose.yml`·`scripts/seed.sh`·`scenarios/lib.sh` — DB 자격증명을 루트 compose 와 같은 계약으로 통일

**종료 (`3bdbbf2`)**

- `job/worker/WorkerDrainGate.kt` (신규) — 디스패처의 문. 플래그가 아니라 락이다
- `job/worker/GenerationWorkerLifecycle.kt` (신규) — `SmartLifecycle`. 문을 닫고 풀을 내리고 기다린다
- `job/worker/WorkerExecutorConfig.kt` — `setWaitForTasksToCompleteOnShutdown(true)`
- `job/worker/GenerationWorker.kt` — 디스패치 주기 전체를 문 안에 넣는다(6줄)
- `global/event/WorkerDrained.kt` (신규) — `DrainOutcome`, `WorkerDrainStarted`, `WorkerDrainCompleted`
- `observability/WorkerDrainMetrics.kt` (신규) — `credit.worker.drain.jobs{outcome=drained|abandoned}`
- `application.yml` — `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase`

## 핵심 코드 읽기

### 1. `V1__baseline.sql` — 스키마를 "개선"하지 않는다

baseline 을 뜰 때 유혹이 하나 있다. 어차피 새로 쓰는 SQL 이니 그동안 아쉬웠던 것(컬럼 순서, 인덱스 이름, `VARCHAR` 폭)을 같이 고치고 싶어진다. 고치지 않았다. baseline 은 **지금 운영 DB 에 실제로 있는 모양**이어야 하고, 다르면 `ddl-auto: validate` 가 부팅에서 죽는다. 개선은 V2 부터다.

그래서 파일은 손으로 쓴 게 아니라 MySQL 8.4 에서 `SHOW CREATE TABLE` 을 받아 적은 것이다. 그 과정에서 예상 밖이었던 것 하나:

```sql
-- @Enumerated(STRING) + MySQL 방언이 만드는 것은 varchar 가 아니라 네이티브 enum 이다.
-- 값의 나열까지 Hibernate 생성물과 같아야 validate 가 통과한다.
status          ENUM ('COMPLETED','FAILED','HOLDING','PROCESSING','REFUNDED') NOT NULL,
```

`@Enumerated(EnumType.STRING)` 이면 `VARCHAR(255)` 가 나올 줄 알았는데 MySQL 방언은 네이티브 `ENUM` 을 만든다. 값의 나열은 알파벳 순이고, 그 순서까지 맞아야 `validate` 가 통과한다. `ledger_entries.type` 도 같다.

이 발견에는 뒤따르는 대가가 있다. `JobStatus` 에 값을 하나 추가하는 것이 이제 **스키마 변경**이 됐다. Java enum 에 상수 하나 넣고 끝이 아니라 `ALTER TABLE jobs MODIFY status ENUM(...)` 마이그레이션이 같이 필요하다. step9 의 원장 재설계에서 바로 만나게 될 비용이다.

### 2. 프로파일 — 기본값을 일부러 주지 않는다

`application.yml` 에 남은 DB 설정은 로컬 개발용 기본값이다. 루트 `docker-compose.yml` 이 띄우는 MySQL 과 **같은 계약**(`credit_system` / `credit` / `credit`)이라, `docker compose up -d` 뒤 `./gradlew bootRun` 이 아무 환경변수 없이 붙는다.

운영은 `application-prod.yml` 로 갈린다.

```yaml
spring:
  # 기본값을 일부러 주지 않는다. 플레이스홀더에 `:기본값` 을 붙이면 환경변수를 빠뜨렸을 때
  # 앱이 로컬 개발용 DB 를 향해 조용히 뜬다. 운영에서 그건 장애를 늦게 발견하게 만들 뿐이라,
  # 값이 없으면 부팅 단계에서 죽는 쪽을 택했다.
  datasource:
    url: ${SPRING_DATASOURCE_URL}
```

`${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/credit_system}` 처럼 쓰면 운영 배포에서 환경변수 하나를 빠뜨렸을 때 앱이 **뜬다**. 뜨는데 아무 데도 못 붙거나, 더 나쁘게는 그 호스트에 마침 있는 다른 DB 에 붙는다. 부팅 실패가 훨씬 싸다 — step6 의 `require(refreshInterval < timeout)` 과 같은 판단이다.

`ddl-auto: validate` 는 `application.yml` 에 이미 있는데도 `application-prod.yml` 에 한 번 더 적었다. 중복이지만, 운영 설정 파일만 보고도 "Hibernate 가 스키마를 안 건드린다"를 확인할 수 있게 못 박은 것이다.

### 3. 테스트는 두 갈래로 갈린다

마이그레이션은 MySQL 문법으로 쓰여 있어서 H2 에서 돌지 않는다. `ENUM (...)` 이 그 예다. 그래서 테스트를 나눴다.

- **H2 를 쓰는 대다수 테스트** — `flyway.enabled: false`, `ddl-auto: create-drop`. 지금까지와 똑같이 Hibernate 가 스키마를 만든다. 빠르고 Docker 가 필요 없다
- **Testcontainers MySQL 을 쓰는 동시성·종료 테스트** — `flyway.enabled: true`, `ddl-auto: validate`. **마이그레이션이 실제로 도는지, 그 결과가 엔티티 매핑과 맞는지를 확인하는 자리는 여기뿐이다**

두 번째 갈래에는 함정이 있었다. 컨테이너가 `withReuse(true)` 라서 지난 실행의 테이블이 그대로 남아 있는데, Flyway 는 이력 테이블 없이 이미 채워진 스키마를 만나면 멈춘다. 매 실행마다 DB 를 비우고 시작하게 했다.

```kotlin
// 컨테이너가 withReuse(true) 라 지난 실행의 테이블이 남아 있다.
// Flyway 는 이력 테이블 없이 채워진 스키마를 만나면 멈추므로 매번 비우고 시작한다.
statement.execute("DROP DATABASE IF EXISTS $database")
statement.execute("CREATE DATABASE $database")
statement.execute("GRANT ALL PRIVILEGES ON $database.* TO 'credit'@'%'")
```

`baseline-on-migrate` 로 넘어가게 만드는 선택지도 있었지만, 그건 "이미 있는 스키마를 V1 이 이미 적용된 것으로 친다"는 뜻이라 **마이그레이션이 실제로 실행되는지를 검증하지 못한다**. 그 검증이 이 갈래의 존재 이유라서 반대로 갔다.

### 4. `Dockerfile` — 빌드를 이미지 안으로 들인다

step7 의 `deploy/observability/Dockerfile` 은 호스트가 만든 fat jar 를 `COPY` 할 뿐이었다. 관측 스택을 띄우는 게 목적이었으니 그때는 그걸로 충분했다. CI 는 "내 노트북에서 `./gradlew bootJar` 를 먼저 돌린다"를 할 수 없다.

옛 주석이 멀티스테이지를 피한 이유로 든 두 가지가 여기서 해소된다.

- **detekt 의 JDK 조합 문제** — 이미지 빌드는 `bootJar` 만 돌린다. detekt 는 CI 가 따로 돌린다. 빌더가 `eclipse-temurin:17-jdk` 이므로 `build.gradle.kts` 의 toolchain 17 은 그냥 만족된다
- **의존성 캐시** — gradle 파일만 먼저 `COPY` 해 의존성 해석을 별도 레이어로 떼어냈다. 소스만 고치면 그 레이어가 재사용된다

jar 를 고르는 데서 한 번 걸렸다.

```dockerfile
# build/libs 에는 fat jar 와 -plain.jar(클래스만 든 jar) 두 개가 나온다.
# 글롭으로 잡으면 -plain.jar 를 집어 `no main manifest attribute` 로 죽는다.
RUN set -eu; \
    jar="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$jar"; \
    cp "$jar" /workspace/app.jar
```

`COPY build/libs/*.jar` 는 두 jar 중 하나를 임의로 집는다. 이름을 `credit_system_kotlin-0.0.1-SNAPSHOT.jar` 로 박으면 버전을 올릴 때마다 Dockerfile 을 고쳐야 한다. `-plain` 만 걸러내는 쪽이 둘 다 피한다.

런타임은 `eclipse-temurin:17-jre` 에 non-root `app` 유저다. uid/gid 를 숫자로 지정하지 않았는데, temurin 의 ubuntu 베이스에 이미 1000 이 있어서 겹치지 않게 시스템 계정으로 만들게 했다. 앱은 파일을 쓰지 않으므로 root 로 돌 이유가 없다.

이 머신(Apple Silicon macOS) 기준으로 캐시 없는 전체 빌드가 **75초**, 결과 이미지가 **394MB** 다. 다른 하드웨어·네트워크에서는 다른 값이 나온다.

### 5. 루트 `docker-compose.yml` — 앱은 기본으로 안 뜬다

기본 사용법은 "인프라는 컨테이너, 앱은 호스트"다.

```
docker compose up -d      # MySQL + Redis 만
./gradlew bootRun         # 앱은 호스트에서
```

개발 중에는 앱을 자주 재시작하고 디버거를 붙이는데, 앱까지 컨테이너에 넣으면 그때마다 이미지를 다시 만들어야 한다. 앱은 profile 뒤에 숨겼다.

```yaml
app:
  # 기본 up 에는 안 뜬다. --profile app 을 줘야 뜬다.
  profiles: ["app"]
```

`--profile app` 으로 띄울 때는 호스트명이 `localhost` 가 아니라 `mysql`·`redis` 가 된다. 이걸 `application.yml` 에 조건부로 넣지 않고 **Spring 표준 환경변수**(`SPRING_DATASOURCE_URL` 등)로 덮어썼다. 설정 파일이 자기가 어디서 도는지 알 필요가 없다.

MySQL 은 3306 을 호스트로 publish 한다(관측 스택과 다른 점이다 — 그쪽은 compose 네트워크 안에만 산다). 호스트의 앱이 붙어야 하기 때문인데, 로컬에 이미 MySQL 이 3306 을 쓰고 있으면 충돌한다. 대가를 알고 고른 쪽이다.

### 6. `ci.yml` / `image.yml` — `workflow_run` 이 아니라 `needs`

"검증이 초록일 때만 이미지가 나간다"를 거는 방법이 둘이었다.

- (a) `workflow_run` — ci 워크플로가 끝나면 image 워크플로가 뒤따라 뜬다
- (b) 같은 실행 안의 job 의존 — `needs:` 로 묶는다

(b)를 골랐다. `workflow_run` 은 **트리거 워크플로가 기본 브랜치에 있을 때만** 뜨는데, 이 저장소의 통합 브랜치는 `develop` 이고 기본 브랜치는 `main` 이다. `develop` 에 올린 워크플로는 `main` 에 머지되기 전까지 한 번도 안 도는 함정에 그대로 걸린다. 게다가 `workflow_run` 의 체크아웃은 기본이 기본 브랜치 HEAD 라 `head_sha` 를 손으로 넘겨야 하고, 실패하면 이미지가 엉뚱한 커밋으로 만들어진다.

파일은 둘로 나눈 채 job 의존을 걸기 위해, `ci.yml` 에 `workflow_call` 을 열어 두고 `image.yml` 이 불렀다.

```yaml
jobs:
  verify:
    uses: ./.github/workflows/ci.yml
  build-and-push:
    needs: verify
```

태그 규칙에서 `latest` 는 붙이지 않았다. 배포는 step10 이고, 그때 무엇을 `latest` 로 부를지 정하지 않은 채 태그부터 만들면 아무도 못 믿는 이름이 하나 생길 뿐이다. `sha-<짧은 sha>` 는 항상 붙는다 — "지금 도는 게 어느 커밋인가"에 답할 수 있어야 한다.

Testcontainers 는 ubuntu 러너의 Docker 데몬으로 그냥 돈다. `withReuse(true)` 는 `~/.testcontainers.properties` 가 있어야 켜지는데 러너에는 없으므로 경고 한 줄 내고 무시된다. 재사용은 로컬에서 반복 실행할 때의 편의이고 CI 는 매번 새 컨테이너로 도는 게 맞아서, 아무 설정도 하지 않았다.

### 7. `WorkerDrainGate` — 플래그가 아니라 락

드레인의 첫 요구는 "이 시점 이후로 새 job 을 선점하지 않는다"이다. 가장 짧은 구현은 `@Volatile var draining` 이고, 그건 틀린다.

```kotlin
fun runIfOpen(dispatchCycle: () -> Unit) {
    lock.withLock {
        if (open) dispatchCycle()
    }
}

/** 진행 중인 디스패치 주기가 끝나기를 기다린 뒤 문을 닫는다. */
fun close() {
    lock.withLock { open = false }
}
```

플래그면 "디스패처가 플래그를 읽은 뒤, 아직 선점하기 전"이라는 창이 남는다. 그 창에서 드레인이 executor 를 내리면 디스패처는 방금 선점한 job 을 넘길 곳이 없어 롤백하고 `WORKER_CLAIM/rolled_back` 을 올린다(step6 이 만든 경로다). **그 경로는 안전망이지 정상 배포의 경로가 아니다.**

문을 락으로 만들면 `close()` 가 진행 중인 디스패치 주기를 기다렸다가 닫히므로, 반환 시점 이후에 새 선점이 없다는 것이 상태가 아니라 **구조로** 보장된다. 드레인은 배포마다 딱 한 번 도는 코드라, 여기서 락 하나를 잡는 비용은 논의할 가치가 없다.

### 8. `GenerationWorkerLifecycle` — phase 를 상수에서 유도한다

스프링의 종료는 `ContextClosedEvent` → `SmartLifecycle.stop`(phase 내림차순) → 빈 소멸(`@PreDestroy`) 순서다. 드레인이 이 사이 어디에 끼는지가 `phase` 하나로 정해진다.

```kotlin
/** 스케줄러와 워커 풀 자신의 lifecycle 보다 **나중에** 멈추기 위한 값 */
val PHASE: Int = ExecutorConfigurationSupport.DEFAULT_PHASE - 1
```

stop 은 phase 내림차순이므로 "나중에 멈춘다 = phase 가 작다"이다. 드레인은 웹서버보다도, 스케줄러/executor 자신의 lifecycle 보다도 나중이어야 한다. 그중 가장 작은 `ExecutorConfigurationSupport.DEFAULT_PHASE`(1073741823)보다 1 작은 값을 쓴다. 숫자를 직접 적지 않고 상수에서 유도해, 스프링이 값을 바꾸면 같이 따라가게 했다.

그 결과 순서는 이렇게 된다.

1. `ContextClosedEvent` — `@Scheduled` 를 굴리는 `taskScheduler` 가 여기서 이미 `shutdown()` 된다. 주기 task 는 더 이상 재예약되지 않는다. 다만 *지금 돌고 있는* 디스패치 주기를 기다려 주지는 않아서, 그 창은 7절의 문이 막는다
2. `SmartLifecycle.stop` — 내장 웹서버가 먼저 멈추고(phase 2147482623/2147481599) 그다음 드레인이 돈다. **HTTP 유입이 끊긴 뒤에 드레인이 시작되므로 드레인 도중에 새 job 이 생기지 않는다**
3. 빈 소멸 — `HeartbeatRegistry.shutdown()` 이 여기서야 불린다

여기서 하나 배웠다. `@Scheduled` 를 멈추는 것은 `ScheduledAnnotationBeanPostProcessor` 가 아니라 **`taskScheduler` 빈**이다. Spring 7 에서 `ScheduledAnnotationBeanPostProcessor` 는 `SmartLifecycle` 이 아니고, `ContextClosedEvent` 에서 이미 내려가는 것은 `ExecutorConfigurationSupport` 의 자식인 `taskScheduler` 쪽이다. phase 를 그쪽 기준으로 잡아야 순서가 맞는다.

3번이 특히 중요하다. `stop()` 이 **동기로 블록**하고 빈 소멸은 모든 lifecycle stop 이 끝난 뒤에 시작되기 때문에, **드레인이 도는 내내 heartbeat 가 살아서 갱신된다.** 이 순서가 깨지면 살아 있는 job 의 heartbeat 가 먼저 끊겨 다른 인스턴스가 그 job 을 회수해 간다 — step10 에서 두 대가 됐을 때 바로 사고가 된다.

### 9. 상한을 새로 만들지 않는다

드레인을 얼마나 기다릴지는 새 설정 키를 만들지 않고 `app.processing.timeout-seconds` 를 그대로 쓴다.

```kotlin
private val drainTimeout: Duration = Duration.ofSeconds(appProperties.processing.timeoutSeconds)
```

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: ${app.processing.timeout-seconds:60}s
```

근거는 두 가지다. 첫째, **그 시간을 넘긴 `PROCESSING` job 은 어차피 `DeadJobRecoveryTask` 의 정체 회수 대상**이라 더 기다려서 얻을 것이 없다. 둘째, 상한을 별도 키로 만들면 두 값이 어긋날 수 있는 자리가 생긴다. 스프링의 phase 상한(`timeout-per-shutdown-phase`)도 같은 프로퍼티에서 유도해, 우리 드레인 상한보다 스프링 상한이 먼저 만료되는 일이 구조적으로 없게 했다.

### 10. `setWaitForTasksToCompleteOnShutdown(true)` 의 부수 효과

이 플래그를 켜는 것이 드레인의 전제다. 기본값(`false`)이면 `ThreadPoolTaskExecutor` 는 종료할 때 `shutdownNow()` 를 불러 생성 중인 스레드를 인터럽트한다. 스텁 호출 중이던 job 은 그 자리에서 죽어 `PROCESSING` 으로 남고 — 이 단계에서 없애려던 문제가 정확히 그것이다.

켜 두면 부수 효과가 하나 더 있고, 그게 오히려 더 중요하다.

> `ExecutorConfigurationSupport` 는 이 플래그가 켜져 있으면 `ContextClosedEvent` 에서 조기 종료를 하지 않고 자신의 `SmartLifecycle.stop` 도 건너뛴다(`lateShutdown`).

즉 이 풀을 **언제 내릴지가 온전히 `GenerationWorkerLifecycle` 의 결정**이 되고, 스프링이 우리보다 먼저 풀을 만지는 창이 사라진다. 실제 종료는 빈 소멸 시점의 `destroy()` 가 마무리하는데, 그때는 이미 드레인이 끝나 있어 할 일이 없다.

기다리는 것 자체는 `setAwaitTerminationSeconds` 에 맡기지 않고 `awaitTermination` 을 직접 부른다. 그쪽은 다 끝났는지 여부를 로그로만 흘리고 호출자에게 돌려주지 않아서, **"몇 개를 포기했나"를 이벤트로 드러낼 수가 없다.**

### 11. 드레인 결과를 이벤트와 카운터로

step7 이 세운 원칙 그대로다. `GenerationWorkerLifecycle` 은 `io.micrometer` 를 모르고 `WorkerDrainStarted` / `WorkerDrainCompleted` 만 발행한다. 세는 책임은 `observability` 패키지의 `WorkerDrainMetrics` 가 진다.

카운터는 하나다. 세는 단위가 "드레인 횟수"가 아니라 **job 수**인 것이 이 지표의 요지다 — 배포가 몇 번 있었는지는 배포 도구가 알고, 지표가 답해야 할 질문은 "그 배포가 job 을 몇 개 회수에 떠넘겼나"다.

```
credit.worker.drain.jobs{outcome=drained}     상한 안에 스스로 끝난 job
credit.worker.drain.jobs{outcome=abandoned}   상한을 넘겨 포기한 job — 회수로 넘어간다
```

`abandoned` 가 0 이 아닌 배포는 드레인 상한이 실제 처리 시간보다 짧다는 신호다. 태그는 하나고 값이 둘이라 카디널리티가 늘어날 자리가 없다(step7 4단계의 가드와 같은 기준).

시작 이벤트를 완료 이벤트와 따로 둔 이유는 **"드레인이 시작된 순간"에만 관찰할 수 있는 것이 있기 때문**이다. 그때는 진행 중 job 이 아직 살아 있고 heartbeat 도 갱신되는 중이다. 완료 이벤트가 나올 때는 둘 다 이미 끝나 있어서 같은 질문을 던질 수 없다.

## 테스트가 보장하는 것

이 단계는 테스트 6개를 더한다(총 187).

- `GenerationWorkerLifecycleTest` (5개, 순수 단위) — `진행 중 job 이 끝날 때까지 stop 이 돌아오지 않는다`(드레인이 동기로 블록한다), `드레인이 끝나면 끝낸 job 수와 걸린 시간을 이벤트로 알린다`, `상한을 넘긴 job 은 포기하고 그 수를 이벤트로 드러낸다`, `드레인이 시작되면 디스패처의 문이 닫힌다`, `phase 는 스케줄러와 워커 풀보다 나중에 멈추는 값이다`. 마지막 하나는 숫자를 하드코딩하지 않고 `ExecutorConfigurationSupport.DEFAULT_PHASE` 와 비교한다

- `GenerationDrainOnShutdownTest` (1개, Testcontainers) — `종료 중에도 진행 중 job 은 완료로 끝나고 새 선점은 없다`. 세 가지를 한 번에 본다. (1) 닫기가 진행 중 job 을 기다렸다가 `COMPLETED` 로 끝낸다, (2) 대기 중이던 두 번째 job 은 드레인 내내 `HOLDING` 으로 남는다, (3) **heartbeat 타임아웃(2초)보다 긴 창(3초) 동안 heartbeat 가 계속 살아 있다**. 3번이 8절의 순서를 검증하는 자리다 — `HeartbeatRegistry` 의 executor 가 드레인보다 먼저 내려갔다면 이 창 안에서 반드시 만료된다

이 테스트가 `@SpringBootTest` 를 안 쓴 이유가 따로 있다. 스프링 테스트 컨텍스트는 컨텍스트를 캐시해 두고 재사용하는데, 이 테스트는 **그 컨텍스트를 닫는 것 자체가 목적**이라 캐시와 정면으로 충돌한다(닫힌 컨텍스트를 되살리려다 `LifecycleProcessor not initialized`). `SpringApplication` 을 직접 띄우면 종료 경로도 실제 애플리케이션의 것 그대로다.

그리고 마이그레이션 자체는 별도 테스트가 없다. Testcontainers 를 쓰는 모든 테스트가 `flyway.enabled: true` + `ddl-auto: validate` 로 뜨는 것이 곧 검증이다 — V1 이 실제 MySQL 에서 실행되지 않거나 결과가 엔티티 매핑과 어긋나면 그 테스트들이 부팅에서 전부 죽는다.

## 실측 — SIGTERM 한 번

`./gradlew bootRun` 으로 띄우고 job 2개를 넣은 뒤 SIGTERM 을 보냈다. 스텁 지연은 15초 고정, `app.worker.concurrency: 1` 이라 job 하나만 진행 중이고 나머지 하나는 `HOLDING` 에서 대기하는 상태를 만들었다.

| 구간 | 시간 | 무엇 |
|---|---|---|
| 웹서버 graceful | 2초 | 진행 중 HTTP 요청 종료. 새 요청 접수 중단 |
| 워커 드레인 | 8.2초 | 진행 중 job 1개가 스스로 끝나기를 기다림 |

결과는 job1 `COMPLETED`, job2 `HOLDING` 유지다. 회수가 한 번도 돌지 않았다.

재현 조건: 이 머신(Apple Silicon macOS), `bootRun`(컨테이너 아님), `app.stub` 지연 15초 고정, `concurrency: 1`, `processing.timeout-seconds: 60`. 8.2초는 "스텁 15초 중 남아 있던 만큼"이라 매번 다르다 — 의미 있는 것은 **상한(60초) 안에 끝났고 `abandoned` 가 0 이었다**는 쪽이다.

부수 효과가 하나 생겼다. `RANDOM_PORT` 를 쓰는 테스트 컨텍스트마다 종료 훅에서 웹서버 graceful 대기 2초가 붙는다. 테스트 전체 시간이 그만큼 늘어난다. 테스트 프로파일에서 `server.shutdown: immediate` 로 되돌릴 수도 있었지만, 그러면 테스트가 검증하는 종료 경로와 실제 종료 경로가 달라진다. 시간을 내주고 같은 경로를 쓰는 쪽을 택했다.

## 여기서도 남는 것

- **CI 가 실제로 돈 적이 없다.** `ci.yml` 과 `image.yml` 은 아직 push 되지 않았다. YAML 문법과 액션 버전은 확인했지만 러너에서 초록이 뜬 것을 본 적은 없다. 로드맵의 step8 완료 기준 "PR 에서 테스트가 초록"은 **미충족**이고, 첫 push 에서 고칠 것이 나올 가능성이 높다. Testcontainers 가 러너 Docker 에서 도는지, 30분 timeout 이 충분한지가 특히 그렇다.
- **2대에서는 스케줄러가 겹친다.** 드레인은 한 인스턴스 안의 문제만 푼다. 회수·대사·멱등키 정리 세 스케줄러에는 여전히 분산 락이 없어서, 인스턴스를 둘로 늘리면 같은 주기에 둘 다 돈다. CAS 덕에 이중 처리는 안 나지만 그게 실측된 적도 없다 — step10 에서 일부러 겹치게 두고 어떤 지표로 드러나는지 본 뒤 ShedLock 을 넣는다.
- **상한을 넘긴 job 은 여전히 회수에 맡긴다.** 드레인은 기다릴 뿐 job 을 안전한 상태로 옮겨 주지 않는다. `processing.timeout-seconds` 를 넘겨도 안 끝난 job 은 `PROCESSING` 으로 남아 다음 인스턴스의 정체 회수를 기다린다. `outcome=abandoned` 카운터가 그 사실을 드러낼 뿐 막지는 않는다.
- **상태 하나 추가가 스키마 변경이 됐다.** `status`·`type` 이 네이티브 `ENUM` 이라 `JobStatus` / `LedgerType` 에 값을 더하려면 `ALTER TABLE ... MODIFY` 마이그레이션이 함께 필요하다. step9 의 원장 재설계에서 바로 부딪힌다.
- **H2 테스트는 마이그레이션을 안 탄다.** 대다수 테스트는 여전히 Hibernate 가 만든 스키마 위에서 돈다. 마이그레이션에 실수가 있어도 Testcontainers 를 쓰는 테스트가 잡아 주지 못하는 영역(그 테스트들이 건드리지 않는 테이블·컬럼)이 남는다.
- **비밀은 저장소에서 사라졌을 뿐이다.** 평문 비밀번호를 걷어냈지만 그 자리를 대신하는 것은 compose 의 환경변수이고, 그것도 평문이다. 진짜 시크릿 관리는 배포 환경이 정해지는 step10 의 몫이다.
- **관측 스택은 여전히 별도 compose 다.** 루트 `docker-compose.yml` 과 `deploy/observability/docker-compose.yml` 이 자격증명 계약만 공유하고 파일은 둘로 남아 있다. 둘을 합치는 판단은 배포 형상이 정해진 뒤에 한다.

## 명령어

```
# 로컬 인프라만 띄우고 앱은 호스트에서
docker compose up -d
./gradlew bootRun
docker compose down -v

# 앱까지 컨테이너로
docker compose --profile app up -d --build

# 이미지만 만들어 보기
docker build -t credit-system-kotlin:local .

# 전체 테스트 (Docker 필요 — Testcontainers MySQL 8.4 / Redis 7)
./gradlew test

# 종료 관련 테스트만
./gradlew test --tests "com.example.credit_system_kotlin.job.worker.GenerationWorkerLifecycleTest"
./gradlew test --tests "com.example.credit_system_kotlin.job.concurrency.GenerationDrainOnShutdownTest"

# CI 가 PR 에서 돌리는 것과 같은 조합
./gradlew test detekt ktlintCheck

# step7 을 머지한 develop 대비 이 단계가 무엇을 더했는지
git diff develop step8-ops --stat
git log --oneline develop..step8-ops
```
