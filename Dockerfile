# 빌드 단계
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /back
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew clean bootJar --no-daemon

# JAR 레이어 추출 단계
FROM eclipse-temurin:21-jre-alpine AS extractor
WORKDIR /back
COPY --from=builder /back/build/libs/weddy-0.0.1-SNAPSHOT.jar weddy.jar
RUN java -Djarmode=layertools -jar weddy.jar extract


# 실행 단계
FROM eclipse-temurin:21-jre-alpine
WORKDIR /back
COPY --from=extractor /back/dependencies/ ./
COPY --from=extractor /back/spring-boot-loader/ ./
COPY --from=extractor /back/snapshot-dependencies/ ./
COPY --from=extractor /back/application/ ./
# 도커 컨테이너가 8080포트를 사용할 것임을 선언
EXPOSE 8080
# 도커 컨테이너가 시작될 때 실행될 고정 명령어
ENTRYPOINT ["java", "-Dspring.profiles.active=local", "org.springframework.boot.loader.launch.JarLauncher"]