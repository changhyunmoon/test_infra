# 빌드 단계
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /back
COPY gradlew .
COPY gradle gradle

RUN chmod +x ./gradlew

COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean bootJar -x test

RUN mkdir -p build/dependency && (cd build/dependency; jar -xf ../libs/*.jar)

# Runtime Stage

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN useradd -ms /bin/bash spring
USER spring:spring
WORKDIR /app

ARG DEPENDENCY=/back/build/dependency

COPY --from=builder ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=builder ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=builder ${DEPENDENCY}/BOOT-INF/classes /app

ENV SERVER_PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Seoul"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=$SERVER_PORT -cp /app:/app/lib/* com.team6.project3th.Project3thApplication"]



