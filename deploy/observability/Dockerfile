# 호스트에서 미리 만든 bootJar 를 그대로 담는다.
#
# 멀티스테이지 Gradle 빌드를 쓰지 않는 이유: 이미지 안에서 Gradle 을 돌리면 의존성 캐시가
# 매번 새로 받아지고, 이 저장소가 detekt 때문에 안고 있는 JDK 조합 문제(데몬 JDK 와
# toolchain 17 의 분리)를 컨테이너 안으로 끌고 들어온다. 관측 스택을 띄우는 것이 목적이지
# 빌드를 재현하는 것이 목적이 아니므로, 빌드는 호스트에 맡긴다.
#
#   ./gradlew bootJar   ← 먼저 이걸 돌려야 한다
FROM eclipse-temurin:17-jre

WORKDIR /app

# 글롭(build/libs/*.jar)을 쓰면 -plain.jar 까지 잡힌다. 실행 가능한 fat jar 이름을 못 박는다.
COPY build/libs/credit-system-kotlin-0.0.1-SNAPSHOT.jar /app/app.jar

# 8080 = 공개 API, 8081 = 관리(액추에이터). compose 는 8080 만 호스트로 publish 한다.
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
