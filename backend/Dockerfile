# syntax=docker/dockerfile:1.7
#
# Cadence backend (Spring Boot fat jar). Build context: ./backend.
#
# Gradle 8.14 (the repo wrapper) cannot run on JDK 25, while the project toolchain is Java 25. So the build stage runs
# Gradle on JDK 21 and is pointed at a JDK 25 copied from the matching image (-Porg.gradle.java.installations.paths);
# toolchain auto-download is disabled in gradle.properties on purpose. The runtime image is a plain JRE 25.

FROM eclipse-temurin:25.0.1_8-jdk-noble AS jdk25

FROM eclipse-temurin:21.0.9_10-jdk-noble AS build
COPY --from=jdk25 /opt/java/openjdk /opt/jdk25
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY modules modules
# Tests run in CI, not in the image build (the integration tests need Docker).
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain \
        -Porg.gradle.java.installations.paths=/opt/jdk25 \
        :modules:app:bootJar -x test \
    && cp modules/app/build/libs/cadence-*.jar /workspace/app.jar

FROM eclipse-temurin:25.0.1_8-jre-noble AS runtime
# Non-root user with a fixed uid so volume/secret permissions are predictable.
RUN groupadd --gid 10001 cadence \
    && useradd --uid 10001 --gid cadence --no-create-home --no-log-init --shell /usr/sbin/nologin cadence
WORKDIR /app
COPY --from=build --chown=cadence:cadence /workspace/app.jar /app/app.jar
USER cadence:cadence
EXPOSE 8080
# Container-aware heap; extra flags via JAVA_OPTS. `exec` makes the JVM PID 1 so it receives SIGTERM (graceful stop).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# The image has no curl/wget: a plain HTTP GET over bash's /dev/tcp, healthy only on {"status":"UP"}.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
