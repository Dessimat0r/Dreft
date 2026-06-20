# HTTP Garden origin-server image for Dreft.
#
# Drop this directory into a clone of https://github.com/narfindustries/http-garden as
# `images/dreft/`, register the service in docker-compose.yml (see README.md), then the Garden's
# differential fuzzer / REPL can target Dreft alongside the other servers.
#
# Builds Dreft from source (parameterized by the Garden's standard APP_REPO / APP_BRANCH / APP_VERSION)
# and runs the org.deftserver.garden.GardenServer echo adapter on port 80. GardenServer lives in Dreft's
# TEST sources, so we `test-compile` (without running the suite) and put target/test-classes on the
# classpath.
FROM http-garden-soil:latest

# --- Toolchain: JDK 25 (Dreft targets <release>25</release>) + Maven, via the Adoptium apt repo ---
RUN apt-get -y update \
 && apt-get -y install --no-install-recommends wget apt-transport-https gnupg ca-certificates git maven \
 && mkdir -p /etc/apt/keyrings \
 && wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | gpg --dearmor > /etc/apt/keyrings/adoptium.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
      > /etc/apt/sources.list.d/adoptium.list \
 && apt-get -y update \
 && apt-get -y install --no-install-recommends temurin-25-jdk \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# --- Fetch Dreft at the requested revision (Garden convention) ---
ARG APP_REPO=https://github.com/Dessimat0r/Dreft.git
ARG APP_BRANCH=master
RUN git clone --branch "$APP_BRANCH" "$APP_REPO" /app/dreft

ARG APP_VERSION=HEAD
WORKDIR /app/dreft
RUN git fetch origin "$APP_BRANCH" \
 && git checkout "$APP_VERSION"

# --- Build: compile main + test classes (GardenServer is a test class), copy runtime deps out ---
RUN mvn -q -B -DskipTests test-compile \
 && mvn -q -B -DskipTests dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

# Dreft listens on 80 inside the Garden network (GardenServer defaults to 80; override with GARDEN_PORT).
# brotjli is an optional, system-scoped dep (vendored at the repo root) so it isn't copied by
# dependency:copy-dependencies — add it to the classpath explicitly.
CMD exec java -cp "/app/dreft/target/classes:/app/dreft/target/test-classes:/app/dreft/target/dependency/*:/app/dreft/brotjli-0.1.0.jar" \
    org.deftserver.garden.GardenServer
