# ==========================================
# 1. Build Stage
# ==========================================
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace/app

# 프로젝트 전체 소스 코드를 복사합니다.
COPY . /workspace/app

# gradlew 실행 권한 부여
RUN chmod +x ./gradlew

# BuildKit 캐시를 활용하여 의존성을 캐싱하고 빌드합니다.
# (GitHub Actions의 type=gha 캐시와 연동되어 빌드 속도가 대폭 향상됩니다)
RUN --mount=type=cache,target=/root/.gradle ./gradlew clean bootJar

# Fat JAR의 압축을 해제하여 lib, META-INF, classes 폴더로 레이어를 분리합니다.
RUN mkdir -p build/dependency && (cd build/dependency; jar -xf ../libs/*-SNAPSHOT.jar)


# ==========================================
# 2. Runtime Stage
# ==========================================
FROM eclipse-temurin:17-jdk-alpine
VOLUME /tmp

# 빌드 스테이지에서 압축 해제한 경로를 변수로 지정합니다.
ARG DEPENDENCY=/workspace/app/build/dependency

# ★ 캐시 히트율 극대화를 위한 레이어 복사 순서 ★
# 1. 외부 라이브러리 (용량이 크고 거의 변하지 않음 -> 가장 먼저 캐시됨)
COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib

# 2. 스프링 부트 설정 메타데이터
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF

# 3. 개발자가 작성한 소스 코드 (가장 자주 변함 -> 캐시가 자주 깨짐)
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app

# 압축이 풀린 클래스 파일들을 클래스패스(-cp)로 지정하여 메인 애플리케이션을 실행합니다.
ENTRYPOINT ["java", "-cp", "app:app/lib/*", "com.example.eumserver.EumServerApplication"]