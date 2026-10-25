# API image: Rust service + the parser JAR + a JRE + the docker CLI (the API starts one runner container per verify call).
FROM maven:3.9-eclipse-temurin-21 AS parser
WORKDIR /src/apps/parser
COPY apps/parser/pom.xml .
COPY apps/parser/src src
RUN mvn -q -B -DskipTests package

FROM rust:1-slim-bookworm AS api
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config curl unzip ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /src/apps/api
COPY apps/api/Cargo.toml apps/api/Cargo.lock ./
COPY apps/api/openapi.yaml openapi.yaml
COPY apps/api/src src
RUN cargo build --release

FROM eclipse-temurin:21-jre-jammy
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=api /src/apps/api/target/release/java-blueprints /usr/local/bin/java-blueprints
COPY --from=parser /src/apps/parser/target/ASTPARSER-1.0-SNAPSHOT.jar /opt/blueprints/parser.jar
ENV BLUEPRINTS_BIND=0.0.0.0:3000 \
    BLUEPRINTS_DATA_DIR=/data \
    BLUEPRINTS_ANALYZER_JAR=/opt/blueprints/parser.jar \
    BLUEPRINTS_JAVA_BIN=java \
    BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21
VOLUME /data
EXPOSE 3000
CMD ["java-blueprints"]
