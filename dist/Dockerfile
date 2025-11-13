#
# CentralDogma Dockerfile
#
FROM eclipse-temurin:17-jre

# Multi-platform build arguments (pre-defined by docker)
ARG TARGETARCH

# Environment variables.
ENV CENTRALDOGMA_HOME "/opt/centraldogma"
ENV CENTRALDOGMA_OPTS ""
ENV JAVA_OPTS "$CENTRALDOGMA_OPTS"
ENV PATH "$CENTRALDOGMA_HOME/bin:$PATH"

# Install CentralDogma binaries and configurations.
RUN mkdir -p                      \
  "$CENTRALDOGMA_HOME"/bin        \
  "$CENTRALDOGMA_HOME"/bin/native \
  "$CENTRALDOGMA_HOME"/conf       \
  "$CENTRALDOGMA_HOME"/lib        \
  "$CENTRALDOGMA_HOME"/licenses   \
  "$CENTRALDOGMA_HOME"/log

COPY build/dist/LICENSE.txt  "$CENTRALDOGMA_HOME"/
COPY build/dist/NOTICE.txt   "$CENTRALDOGMA_HOME"/
COPY build/dist/README.md    "$CENTRALDOGMA_HOME"/
COPY build/dist/bin/common* build/dist/bin/dogma build/dist/bin/shutdown build/dist/bin/startup \
  "$CENTRALDOGMA_HOME"/bin/
COPY build/dist/conf/*       "$CENTRALDOGMA_HOME"/conf/
COPY build/dist/lib/*        "$CENTRALDOGMA_HOME"/lib/
COPY build/dist/licenses/*   "$CENTRALDOGMA_HOME"/licenses/

COPY --chmod=755 \
  build/dist/bin/native/dogma.linux_${TARGETARCH} \
  "$CENTRALDOGMA_HOME"/bin/native/

# Expose ports.
EXPOSE 36462

# Healthcheck – waits for the HTTP API to respond
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=10 \
  CMD curl -f http://127.0.0.1:36462/monitor/l7check || exit 1

# Entrypoint doesn't allow an environment variable.
ENTRYPOINT ["/opt/centraldogma/bin/startup", "-nodetach"]
