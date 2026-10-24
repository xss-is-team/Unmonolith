FROM eclipse-temurin:21-jdk-jammy

RUN groupadd --gid 65532 runner \
    && useradd --uid 65532 --gid 65532 --no-create-home --shell /usr/sbin/nologin runner

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

USER 65532:65532
WORKDIR /work

CMD ["sleep", "300"]
