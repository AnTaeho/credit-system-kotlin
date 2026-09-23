plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
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
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
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
// 이유는 `git show 589c696^:report.md` J절 참고 — detekt 1.23.8 이 물고 있는 Kotlin 2.0.21 은
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

// ── 부하 직후 검사 ──────────────────────────────────────────────────────────
// `post-load` 태그가 붙은 테스트는 Testcontainers 가 아니라 compose 로 띄운 로컬 DB
// (localhost:3306/credit_system)에 붙는다. 부하를 받은 바로 그 DB 를 봐야 하기 때문이다.
// 평소 `./gradlew test` 에서는 DB 가 없거나 비어 있으므로 제외하고, 부하 직후에만 돌린다.
val postLoadTag = "post-load"

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags(postLoadTag)
    }
}

tasks.register<Test>("postLoadCheck") {
    group = "verification"
    description = "부하 직후 로컬 DB 에 대고 INV-01/INV-02 검사를 돌린다(compose 로 띄운 DB 필요)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags(postLoadTag)
    }
    // 부하를 또 준 뒤 다시 돌리는 것이 이 태스크의 쓸모다. 입력이 안 바뀌었다고 건너뛰면
    // 아무것도 안 찍힌 채 UP-TO-DATE 만 남는다.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
