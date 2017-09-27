#
# CentralDogma Dockerfile
#
FROM java:openjdk-8-jre

# Environment variables.
ENV CENTRALDOGMA_HOME "/opt/centraldogma"
ENV CENTRALDOGMA_OPTS "-nodetach"
ENV JAVA_HOME /usr/lib/jvm/java-8-openjdk-amd64/
ENV JAVA_OPTS "$CENTRALDOGMA_OPTS"

# Install CentralDogma binaries and configurations.
RUN mkdir -p                    \
  "$CENTRALDOGMA_HOME"/bin      \
  "$CENTRALDOGMA_HOME"/conf     \
  "$CENTRALDOGMA_HOME"/lib      \
  "$CENTRALDOGMA_HOME"/licenses \
  "$CENTRALDOGMA_HOME"/log

COPY build/dist/LICENSE.txt "$CENTRALDOGMA_HOME"/
COPY build/dist/NOTICE.txt  "$CENTRALDOGMA_HOME"/
COPY build/dist/README.md   "$CENTRALDOGMA_HOME"/
COPY build/dist/bin/*       "$CENTRALDOGMA_HOME"/bin/
COPY build/dist/conf/*      "$CENTRALDOGMA_HOME"/conf/
COPY build/dist/lib/*       "$CENTRALDOGMA_HOME"/lib/
COPY build/dist/licenses/*  "$CENTRALDOGMA_HOME"/licenses/

# Expose ports.
EXPOSE 36462

CMD ["sh", "-c", "${CENTRALDOGMA_HOME}/bin/startup.sh"]
