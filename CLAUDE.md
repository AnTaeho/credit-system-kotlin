# credit-system-kotlin — 작업 지침

## 작업 기준
- step 시리즈(stepN 브랜치)는 버렸다. `step11-external` 코드(타임아웃·절대 상한·backoff·드레인·상관 ID)도 머지하지 않는다. roadmap·credit-todo 문서가 step11 머지를 할 일처럼 적고 있어도 따르지 않는다.
- 새 작업은 `develop`에 바로 커밋한다. develop 마이그레이션은 V1~V4.
- 포트폴리오 6장의 「외부 호출 타임아웃이 필요합니다」 한계는 그대로 둔다. step10(배포 1대, 도메인 credit.papercut.kr)은 보류.

## 테스트 함정
- 테스트 기본은 H2(Hibernate가 스키마 생성, Flyway 안 탐). MySQL 전용 제약·마이그레이션은 `SharedContainers.registerDatabase`를 쓰는 Testcontainers 테스트로 확인(`LedgerTypeMigrationTest` 참고).
- 테스트 준비 `repo.save(...)`를 프로퍼티 초기화에 두지 않는다. 트랜잭션 밖에서 커밋돼 롤백되지 않는다. `lateinit var` + `@BeforeEach`.
- Testcontainers self-type 제네릭(`GenericContainer<Nothing>`)은 빌더 반환이 Nothing이 된다. `class RedisContainer(img) : GenericContainer<RedisContainer>(img)`로 묶는다.
- `@DynamicPropertySource`는 `companion object` + `@JvmStatic`.
- 브랜치를 바꿔 테스트 개수를 볼 때는 `rm -rf build/test-results/test` 먼저. 안 지우면 개수가 부풀려진다.
