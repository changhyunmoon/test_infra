# ==========================================
# 1. Build Stage
# ==========================================
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# 의존성 파일만 먼저 복사하여 레이어 캐시 히트율 극대화
COPY gradlew ./
COPY gradle/ ./gradle/
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

# ★ 핵심 1: BuildKit 캐시 마운트를 이용한 의존성 다운로드
# 컨테이너가 종료되어도 /root/.gradle 디렉토리의 파일들을 보존합니다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies || true

# 소스 코드 복사 및 빌드
COPY src ./src
# ★ 핵심 2: 빌드 시에도 캐시를 마운트하여 기존 다운로드된 라이브러리 재사용
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean bootJar -x test

# Fat JAR 압축 해제를 통한 레이어 분리 준비
RUN mkdir -p build/dependency && (cd build/dependency; jar -xf ../libs/*.jar)


# ==========================================
# 2. Runtime Stage
# ==========================================
FROM eclipse-temurin:17-jre-jammy AS runtime

# 보안을 위한 비루트 사용자 설정
RUN useradd -ms /bin/bash spring
USER spring:spring
WORKDIR /app

ARG DEPENDENCY=/workspace/build/dependency

# ★ 핵심 3: 변동성이 적은 순서대로 레이어를 복사 (앱 실행 속도 및 배포 최적화)
COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app

ENV SERVER_PORT=8000
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Seoul"
EXPOSE 8000

# 분리된 클래스패스를 이용해 애플리케이션 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=$SERVER_PORT -cp app:app/lib/* com.example.YourApplicationName"]
# 주의: com.example.YourApplicationName 부분은 실제 메인 클래스 경로로 변경해주세요.