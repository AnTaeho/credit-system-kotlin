# credit-system-kotlin — 작업 지침

## 작업 기준
- step 시리즈(stepN 브랜치)는 버렸고 그 문서(`STEPS.md`, `docs/step*.md`, `docs/roadmap.md`)도 지웠다. 다시 만들지 않는다. `step11-external` 코드(타임아웃·절대 상한·backoff·드레인·상관 ID)도 머지하지 않는다.
- 새 작업은 `develop`에 바로 커밋한다. develop 마이그레이션은 V1(baseline)~V4.
- 포트폴리오 6장의 「외부 호출 타임아웃이 필요합니다」 한계는 그대로 둔다. step10(배포 1대, 도메인 credit.papercut.kr)은 보류.

## 테스트 함정
- 테스트 기본은 H2(Hibernate가 스키마 생성, Flyway 안 탐). MySQL 전용 제약·마이그레이션은 `SharedContainers.registerDatabase`를 쓰는 Testcontainers 테스트로 확인(`LedgerTypeMigrationTest` 참고).
- 테스트 준비 `repo.save(...)`를 프로퍼티 초기화에 두지 않는다. 트랜잭션 밖에서 커밋돼 롤백되지 않는다. `lateinit var` + `@BeforeEach`.
- Testcontainers self-type 제네릭(`GenericContainer<Nothing>`)은 빌더 반환이 Nothing이 된다. `class RedisContainer(img) : GenericContainer<RedisContainer>(img)`로 묶는다.
- `@DynamicPropertySource`는 `companion object` + `@JvmStatic`.
- 테스트에서 spring-security-test 의 `csrf()`를 쓰지 않는다. 한 번 쓰이면 그 컨텍스트의 CSRF 저장소를 세션 저장소로 바꿔 끼우고 되돌리지 않아, 뒤따르는 테스트가 순서에 따라 깨진다. `support/TestCsrf.kt`의 `withCsrfToken()`을 쓴다.
- 테스트 인증은 `support/TestTokens.kt`로 만든 Bearer 헤더나 쿠키를 쓴다. `X-Dev-User` 헤더와 `oidcLogin()`은 없어졌다.
- 브랜치를 바꿔 테스트 개수를 볼 때는 `rm -rf build/test-results/test` 먼저. 안 지우면 개수가 부풀려진다.

## 로컬 실행 함정
- 이 머신에는 Homebrew mysqld·redis 가 `127.0.0.1:3306`·`6379`에 떠 있어 `localhost`가 컴포즈 컨테이너 대신 그쪽으로 간다. `bootRun`은 `SPRING_DATASOURCE_URL='jdbc:mysql://[::1]:3306/credit_system' SPRING_DATA_REDIS_HOST='::1'`을 붙여 띄운다. Homebrew 서비스는 건드리지 않는다.
