ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
WORKDIR /app
USER root
RUN id -u polygate >/dev/null 2>&1 || useradd --system --uid 10001 --home /app polygate
COPY --chown=polygate:polygate target/polygate-1.0.0.jar /app/polygate.jar
USER polygate
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/polygate.jar"]
