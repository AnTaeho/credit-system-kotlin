# 저장소 루트 이미지 — 소스만 있으면 이 파일 하나로 실행 가능한 이미지가 나온다.
#
# step7 의 deploy/observability/Dockerfile 은 호스트가 만든 fat jar 를 COPY 했다.
# 관측 스택을 띄우는 게 목적이었으니 그때는 그걸로 충분했지만, CI 는 "내 노트북에서
# ./gradlew bootJar 를 먼저 돌린다"를 할 수 없다. 그래서 빌드를 이미지 안으로 들인다.
#
# 옛 주석이 멀티스테이지를 피한 이유로 든 두 가지는 여기서 해소된다.
#   - detekt 의 JDK 조합 문제: 이미지 빌드는 bootJar 만 돌린다. detekt 는 CI 가 따로 돌린다.
#     빌더가 JDK 17 이므로 build.gradle.kts 의 toolchain 17 은 그냥 만족된다.
#   - 의존성 캐시: gradle 파일만 먼저 COPY 해 의존성 해석을 별도 레이어로 떼어냈다.
#     소스만 고치면 그 레이어는 재사용되고, 다시 받는 건 build.gradle.kts 를 건드릴 때뿐이다.

# ── 빌더 ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /workspace

# 1) 빌드 스크립트와 래퍼만 먼저. 이 레이어가 안 바뀌면 아래 의존성 다운로드도 안 돈다.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./

# 2) 의존성만 미리 받아 레이어로 굳힌다.
#    runtimeClasspath 가 이미지에 실제로 들어가는 덩어리다. 나머지(kotlin 컴파일러 등)는
#    bootJar 때 채워지지만, 양의 대부분은 여기서 끝난다.
#    네트워크가 막힌 빌드 환경에서도 여기서 죽지 않도록 실패를 삼킨다 — 진짜로 필요한
#    의존성이 없으면 아래 bootJar 가 정직하게 실패한다.
RUN chmod +x gradlew \
 && ./gradlew --no-daemon --console=plain dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

# 3) 소스. 여기부터가 커밋마다 바뀌는 부분이다.
COPY src ./src

# 테스트는 Testcontainers(Docker) 가 필요하고 detekt 는 이미지에 들어갈 산출물과 무관하다.
# 이미지 빌드는 bootJar 하나만 한다. 검증은 CI 워크플로의 몫이다.
RUN ./gradlew --no-daemon --console=plain bootJar -x test

# build/libs 에는 fat jar 와 -plain.jar(클래스만 든 jar) 두 개가 나온다.
# 글롭으로 잡으면 -plain.jar 를 집어 `no main manifest attribute` 로 죽는다.
# 버전이 올라도 따라가도록 이름을 박지 않고 -plain 만 걸러낸다.
RUN set -eu; \
    jar="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$jar"; \
    cp "$jar" /workspace/app.jar

# ── 런타임 ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

# root 로 돌 이유가 없다. 앱은 파일을 쓰지 않는다.
# uid/gid 를 지정하지 않는다 — temurin 의 ubuntu 베이스에는 이미 1000 이 있다.
RUN groupadd --system app \
 && useradd --system --gid app --no-create-home app

WORKDIR /app
COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

USER app

# 8080 = 공개 API, 8081 = 관리(액추에이터).
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
