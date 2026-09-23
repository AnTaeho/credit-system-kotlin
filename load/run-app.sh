#!/usr/bin/env bash
#
# 측정용 앱 기동 — 부하 실측(3-D)에서만 쓴다.
#
# 앱은 호스트 JVM 으로 띄운다(요구서 합의 4, 2026-09-22). Docker 로는 JVM 자원을 못 묶으므로
# 여기서 **JVM 플래그로** 묶는다. 컨테이너 쪽 상한은 load/docker-compose.load.yml 이 맡는다.
#
# 사용법:
#   load/run-app.sh b     # 프로파일 (b) 지연 50~100ms, 실패율 0  — 01·02·04 가 쓴다
#   load/run-app.sh a     # 프로파일 (a) application.yml 기본값(3~7초, 0.3) — 03 이 쓴다
#
# 왜 이 값들인가.
# -Xms = -Xmx : 힙이 측정 중에 자라지 않게 한다. 기본값은 호스트 RAM 의 1/4(약 4GiB)이고
#               자동으로 늘었다 줄었다 하므로 두 실행이 같은 조건이 아니다.
# 2g          : 이 앱은 요청당 만드는 객체가 작다. 2GiB 면 Full GC 없이 10분을 돈다는 가정이고,
#               실제로 도는지는 결과 파일의 GC 칸에서 확인한다(가정, 미검증).
# AlwaysPreTouch : 기동 때 힙 페이지를 다 만져 둔다. 부하 중에 페이지 폴트로 지연이 튀는 것을 막는다.
# ActiveProcessorCount=4 : 호스트 10코어 중 4개만 쓰는 것으로 **계산한다**. GC 스레드 수와
#               톰캣 기본 스레드 수가 이 값에서 유도되므로, 이것을 고정하지 않으면 k6·DB 와
#               코어를 나눠 쓰는 상황에서 매 실행 구성이 달라진다.
# -XX:+UseG1GC: JDK 26 기본이지만 명시한다. 결과 파일에 적을 사실을 코드가 갖고 있게 한다.
# Xlog:gc     : GC 로그를 남긴다. "느려진 게 GC 때문인가"를 나중에 물을 수 있게.
#
# **Hikari 는 건드리지 않는다.** 기본 최대 10 이 현재 구현이고, 그것을 바꾸면 측정 대상이 바뀐다.

set -euo pipefail

PROFILE="${1:?사용법: run-app.sh <a|b>}"
cd "$(dirname "$0")/.."

LOG_DIR="build/load-logs"
mkdir -p "$LOG_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)

export SPRING_PROFILES_ACTIVE=local

# 측정용 인프라는 3307/6380 에 있다(load/docker-compose.load.yml). 기본값 3306/6379 에는
# 이 머신의 homebrew mysqld·redis 가 이미 붙어 있어서, 그대로 두면 엉뚱한 DB 를 재게 된다.
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://127.0.0.1:3307/credit_system}"
export SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-127.0.0.1}"
export SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-6380}"

# 허용 목록. k6 가 쓰는 계정과 운영자를 함께 넣는다(운영자를 빼면 AuthStartupGuard 가 기동을 거부한다).
LOAD_USERS="${LOAD_USERS:-load01@local.test,load02@local.test,load03@local.test,load04@local.test,load05@local.test}"
export APP_AUTH_ALLOWEDEMAILS="admin@local.test,dev@local.test,${LOAD_USERS}"
export APP_AUTH_ADMINEMAILS='admin@local.test'

case "$PROFILE" in
  b)
    # 대시가 빠진 이름이어야 한다. APP_STUB_MIN_DELAY_MILLIS 는 조용히 무시된다.
    export APP_STUB_MINDELAYMILLIS=50
    export APP_STUB_MAXDELAYMILLIS=100
    export APP_STUB_FAILURERATE=0
    ;;
  a)
    # application.yml 기본값을 그대로 쓴다. 아무것도 내보내지 않는다.
    ;;
  *)
    echo "프로파일은 a 또는 b 다: $PROFILE" >&2
    exit 1
    ;;
esac

JVM_ARGS=(
  -Xms2g -Xmx2g
  -XX:+AlwaysPreTouch
  -XX:ActiveProcessorCount=4
  -XX:+UseG1GC
  "-Xlog:gc*:file=${PWD}/${LOG_DIR}/gc-${PROFILE}-${STAMP}.log:time,uptime:filecount=0"
)

# bootRun 이 아니라 jar 를 직접 띄운다. bootRun 은 Gradle 데몬이 같은 기계에서 계속 돌고
# JVM 플래그를 넘기려면 빌드 스크립트를 고쳐야 한다. jar 를 띄우면 측정 대상 프로세스가
# 하나로 깨끗해지고, 플래그도 여기서 그대로 준다.
echo "jar 빌드 중..."
./gradlew -q bootJar
JAR=$(ls -t build/libs/*-SNAPSHOT.jar | head -1)

echo "측정용 앱 기동: 프로파일=$PROFILE"
echo "  jar: $JAR"
echo "  JVM: ${JVM_ARGS[*]}"
echo "  스텁: min=${APP_STUB_MINDELAYMILLIS:-기본값} max=${APP_STUB_MAXDELAYMILLIS:-기본값} fail=${APP_STUB_FAILURERATE:-기본값}"
echo "  GC 로그: ${LOG_DIR}/gc-${PROFILE}-${STAMP}.log"
echo "  DB: ${SPRING_DATASOURCE_URL:-application.yml 기본값(localhost:3306)}"
echo "  자바: $(java -version 2>&1 | head -1)"
echo

exec java "${JVM_ARGS[@]}" -jar "$JAR"
