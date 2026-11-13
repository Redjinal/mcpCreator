# Runtime-only image. Build the distribution first on the build host:
#   (cd server && ./gradlew installDist)
# This keeps the image free of build tooling and avoids needing Maven access
# inside the image build.
FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
RUN groupadd --system pi && useradd --system --gid pi --home /opt/server pi
COPY --chown=pi:pi build/install/server /opt/server
USER pi
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 CMD ["/opt/server/bin/server", "--healthcheck"]
ENTRYPOINT ["/opt/server/bin/server"]
