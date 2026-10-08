plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-mysql")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // 액세스 JWT 를 직접 내고 확인하는 데 Nimbus 인코더·디코더만 쓴다. 리소스 서버 자동 설정은 쓰지 않는다.
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mysql:2.0.5")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

// ── detekt ─────────────────────────────────────────────────────────────────
// Gradle 플러그인을 쓰지 않고 detekt-cli 를 별도 JVM 에서 돌린다.
// 이유는 report.md J 절 참고 — detekt 1.23.8 이 물고 있는 Kotlin 2.0.21 은
// 이 머신의 실행 JDK(26)를 거부한다. 플러그인의 Detekt 태스크는 Gradle 데몬 안에서
// 돌아서 데몬 JDK 를 통째로 내리지 않으면 못 쓴다. JavaExec 로 분리하면
// 데몬은 26 그대로 두고 detekt 만 17 에서 돌릴 수 있다.
val detektCli: Configuration by configurations.creating

dependencies {
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

// detekt 1.23.8 은 Kotlin 2.0.21 로 빌드되어 있다. 이 프로젝트의 2.3.21 이
// detektCli 컨피규레이션까지 올라오면 컴파일러 내부 클래스가 어긋나 깨진다.
// detekt 쪽 클래스패스에서만 2.0.21 로 되돌린다. 프로젝트 컴파일에는 영향이 없다.
configurations.named("detektCli") {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.0.21")
        }
    }
}

val detektLauncher = project.extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

val detekt by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "detekt 로 미사용 임포트 등 정적 분석을 수행한다"
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    javaLauncher.set(detektLauncher)
    val reportDir = layout.buildDirectory.dir("reports/detekt")
    inputs.dir(layout.projectDirectory.dir("src"))
    inputs.file(layout.projectDirectory.file("detekt.yml"))
    outputs.dir(reportDir)
    doFirst { reportDir.get().asFile.mkdirs() }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--input", "src/main/kotlin,src/test/kotlin",
                "--config", "detekt.yml",
                "--build-upon-default-config",
                "--report", "xml:${reportDir.get().asFile}/detekt.xml",
                "--report", "txt:${reportDir.get().asFile}/detekt.txt"
            )
        }
    )
}

tasks.named("check") { dependsOn(detekt) }

ktlint {
    version.set("1.8.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// ── 뮤테이션 테스트(PIT) ───────────────────────────────────────────────────
// main 코드를 한 군데씩 바꿔 보고(조건 뒤집기, 반환값 바꾸기, 호출 지우기) 테스트가 그걸 잡는지 잰다.
// 변이마다 테스트를 다시 돌려 느리므로 check·CI 에 걸지 않는다. 손으로 `./gradlew pitest` 를 돌린다.
// 범위는 Docker 없이 도는 테스트다. Testcontainers 테스트(MySQL·Redis)는 빼서,
// 그 테스트만 덮는 코드(리프레시 토큰의 Redis 흐름 등)는 「닿지 않음」으로 나온다.
pitest {
    pitestVersion.set("1.30.0")
    // JUnit Platform 6.0.3 에서 도는 것을 직접 확인한 버전이다.
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("com.example.credit_system_kotlin.*"))
    targetTests.set(listOf("com.example.credit_system_kotlin.*"))
    // 바꿔 봐도 뜻이 없는 것: 요청·응답 DTO, 설정 프로퍼티, @Configuration, 애플리케이션 진입점.
    excludedClasses.set(
        listOf(
            "com.example.credit_system_kotlin.*.dto.*",
            "com.example.credit_system_kotlin.*.config.*",
            "com.example.credit_system_kotlin.*Properties*",
            "com.example.credit_system_kotlin.*Config",
            "com.example.credit_system_kotlin.CreditSystemKotlinApplication*"
        )
    )
    // SharedContainers 를 쓰는 테스트. Docker 가 없으면 실패하고, PIT 는 테스트 전체가 초록이어야 돈다.
    excludedTestClasses.set(
        listOf(
            "com.example.credit_system_kotlin.job.concurrency.*",
            "com.example.credit_system_kotlin.auth.account.InitialAccountsMigrationTest",
            "com.example.credit_system_kotlin.auth.token.AuthTokenMigrationTest",
            "com.example.credit_system_kotlin.auth.token.RefreshTokenServiceTest",
            "com.example.credit_system_kotlin.job.repository.JobsUserIdIndexMigrationTest",
            "com.example.credit_system_kotlin.ledger.repository.LedgerJobGuardMigrationTest",
            "com.example.credit_system_kotlin.ledger.repository.LedgerTypeMigrationTest",
            "com.example.credit_system_kotlin.web.LoginFlowTest"
        )
    )
    // 기본 묶음. 다른 묶음은 `./gradlew pitest -Ppit.mutators=STRONGER` 처럼 쉼표로 이어 준다.
    mutators.set(providers.gradleProperty("pit.mutators").getOrElse("DEFAULTS").split(","))
    // 로그 호출과 Kotlin 컴파일러가 넣는 널 검사(Intrinsics)를 지우는 변이는 만들지 않는다.
    avoidCallsTo.set(
        listOf(
            "kotlin.jvm.internal",
            "org.slf4j",
            "java.util.logging",
            "org.apache.log4j",
            "org.apache.commons.logging"
        )
    )
    threads.set(6)
    jvmArgs.set(listOf("-Xmx1g"))
    // 변이 JVM 마다 스프링 컨텍스트가 새로 뜬다. 그 시간을 시간초과로 세지 않게 넉넉히 둔다.
    timeoutConstInMillis.set(30000)
    timestampedReports.set(false)
    outputFormats.set(listOf("HTML", "XML"))
}
