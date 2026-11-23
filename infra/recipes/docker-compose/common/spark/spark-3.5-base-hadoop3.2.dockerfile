# The Spark image of every recipe: Spark 3.5 on Java 17 with the build's Iceberg runtime and the
# OpenHouse jars of `./gradlew dockerPrereqs` (docs/development/iceberg-fork.md, "Docker images").
#
# Which stage provides /opt/livy and /opt/spark: "dockerhub", the stages that take Spark from
# Apache's Docker image and build Livy in Maven's image, or "default", the builder stage below.
# The builder stage installs some 200 Ubuntu packages and downloads Spark and Maven from
# archive.apache.org. Where those hosts are slow the build takes hours (on GitHub's runners:
# archive.apache.org at 46 kB/s, apt-get 9 and 42 minutes). The "dockerhub" stages need only
# Docker Hub, GitHub and Maven Central.
#
# Livy's own build would download one more Spark release (spark-3.1.1-bin-hadoop2.7.tgz, 228 MB)
# from archive.apache.org, in its thriftserver and integration-test modules, for tests that
# -DskipTests skips. -Ddownload.plugin.skip=true keeps both stages from doing it: the property
# of the skip parameter of download-maven-plugin (1.3.0 in Livy's pom). The log then says
# "maven-download-plugin:wget skipped".
ARG OH_SPARK_BUILDER=dockerhub

FROM eclipse-temurin:11-jdk-jammy as builder

# Update package lists and install packages with proper security
RUN apt-get update && apt-get install -y --no-install-recommends \
    git curl vim zip unzip software-properties-common ssh net-tools ca-certificates \
    # Add Dependencies for PySpark \
    python3 python3-pip python3-numpy python3-matplotlib python3-scipy python3-pandas python3-simpy && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

RUN update-alternatives --install "/usr/bin/python" "python" "$(which python3)" 1

# Fix the value of PYTHONHASHSEED
# Note: this is needed when you use Python 3.3 or greater
ENV SPARK_VERSION=3.5.2 \
LIVY_VERSION=0.8.0-incubating-SNAPSHOT \
LIVY_HOME=/opt/livy \
HADOOP_VERSION=3 \
SPARK_HOME=/opt/spark \
MAVEN_VERSION=3.9.4 \
PYTHONHASHSEED=1

# install apache spark
RUN curl --no-verbose -o apache-spark.tgz \
    "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop${HADOOP_VERSION}.tgz" \
  && mkdir -p /opt/spark \
  && tar -xf apache-spark.tgz -C /opt/spark --strip-components=1 \
  && rm apache-spark.tgz

# install maven to build apache livy
RUN curl --no-verbose -o maven.tgz https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
  && mkdir -p /opt/maven \
  && tar -xf maven.tgz -C /opt/maven --strip-components=1 \
  && rm maven.tgz

# build with patch and install apache livy
ARG ARCHIVE_FILENAME="apache-livy-${LIVY_VERSION}-bin"
COPY /infra/recipes/docker-compose/common/spark/livy_spark3_hadoop3.patch /
RUN git clone https://github.com/apache/incubator-livy \
    && cd incubator-livy \
    && git checkout 4d8a912699683b973eee76d4e91447d769a0cb0d \
    && git apply /livy_spark3_hadoop3.patch \
    && rm /livy_spark3_hadoop3.patch \
    && sed -i 's#apache-incubator-disclaimer-resource-bundle:1.2-SNAPSHOT#apache-incubator-disclaimer-resource-bundle:1.5#' pom.xml \
    && /opt/maven/bin/mvn clean package -B -V -e -Pspark-3.0 -Pthriftserver -DskipTests -DskipITs -Dmaven.javadoc.skip=true \
       -Ddownload.plugin.skip=true \
    && unzip -qq ./assembly/target/${ARCHIVE_FILENAME}.zip -d /opt \
    && mv "/opt/${ARCHIVE_FILENAME}" "${LIVY_HOME}" \
    && rm -rf "/incubator-livy"

# OH_SPARK_BUILDER=dockerhub. The same Livy commit, patch and Maven version on the same Java, built
# in Maven's image, which has git and needs no package installed. Maven builds every module but
# integration-test and coverage (which reports on it); neither is part of the distribution.
FROM maven:3.9.4-eclipse-temurin-11 AS livy-dockerhub
ENV LIVY_VERSION=0.8.0-incubating-SNAPSHOT \
LIVY_HOME=/opt/livy
ARG ARCHIVE_FILENAME="apache-livy-${LIVY_VERSION}-bin"
COPY /infra/recipes/docker-compose/common/spark/livy_spark3_hadoop3.patch /
# The image has no unzip: the JDK's jar extracts the distribution, without the scripts' mode.
RUN git clone https://github.com/apache/incubator-livy /incubator-livy \
    && cd /incubator-livy \
    && git checkout 4d8a912699683b973eee76d4e91447d769a0cb0d \
    && git apply /livy_spark3_hadoop3.patch \
    && rm /livy_spark3_hadoop3.patch \
    && sed -i 's#apache-incubator-disclaimer-resource-bundle:1.2-SNAPSHOT#apache-incubator-disclaimer-resource-bundle:1.5#' pom.xml \
    && mvn clean package -B -V -e --no-transfer-progress -Pspark-3.0 -Pthriftserver -DskipTests -DskipITs -Dmaven.javadoc.skip=true \
       -Ddownload.plugin.skip=true -pl '!integration-test,!coverage' \
    && mkdir /opt/livy-dist \
    && cd /opt/livy-dist \
    && jar xf "/incubator-livy/assembly/target/${ARCHIVE_FILENAME}.zip" \
    && mv "/opt/livy-dist/${ARCHIVE_FILENAME}" "${LIVY_HOME}" \
    && chmod +x "${LIVY_HOME}"/bin/* \
    && ls -l "${LIVY_HOME}" "${LIVY_HOME}/bin" \
    && rm -rf /incubator-livy /opt/livy-dist

FROM builder AS spark-builder-default

# The Spark release of the builder stage (keep it equal to SPARK_VERSION there), from Apache's
# Docker image of it: /opt/spark holds what the tarball's does but for the configuration templates,
# the licenses and the YARN shuffle jar.
FROM apache/spark:3.5.2-scala2.12-java17-python3-r-ubuntu AS spark-builder-dockerhub
COPY --from=livy-dockerhub /opt/livy /opt/livy

FROM spark-builder-${OH_SPARK_BUILDER} AS spark-builder


FROM bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8

# The OpenHouse jars are Java 17 class files, so every JVM in the image (Spark, Livy, the token
# generator below) runs on a Java 17 runtime, not on the base image's Java 8.
COPY --from=eclipse-temurin:17-jre-jammy /opt/java/openjdk /opt/java/openjdk-17
ENV JAVA_HOME=/opt/java/openjdk-17
ENV PATH=$JAVA_HOME/bin:$PATH
# Spark opens these packages for its own JVMs. The Livy server needs them opened too on Java 17:
# without java.lang.invoke its RPC serializer fails and every session dies on connect.
ENV LIVY_SERVER_JAVA_OPTS="\
--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.nio=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
--add-opens=java.base/sun.security.action=ALL-UNNAMED \
--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
# The build's Iceberg runtime, which `./gradlew dockerPrereqs` stages (no public repository serves
# the fork's): every Spark application in the image has it without `--packages`.
COPY /build/iceberg-spark-runtime/ /opt/spark/jars/

ENV LIVY_HOME=/opt/livy \
SPARK_HOME=/opt/spark

COPY --from=spark-builder /opt/livy /opt/livy
COPY --from=spark-builder /opt/spark /opt/spark

WORKDIR $SPARK_HOME

ENV HADOOP_HOME=/opt/hadoop-3.2.1
ENV HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop
ENV SPARK_MASTER_PORT=7077 \
SPARK_MASTER_WEBUI_PORT=8080 \
SPARK_LOG_DIR=$SPARK_HOME/logs
ENV SPARK_MASTER_LOG=$SPARK_LOG_DIR/spark-master.out \
SPARK_WORKER_LOG=$SPARK_LOG_DIR/spark-worker.out \
SPARK_WORKER_WEBUI_PORT=8080 \
SPARK_WORKER_PORT=7000 \
SPARK_MASTER="spark://spark-master:7077"
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318" \
OTEL_EXPORTER_OTLP_METRICS_PROTOCOL="http"

EXPOSE 8080 7077 7000 8998

RUN mkdir -p $SPARK_LOG_DIR && \
touch $SPARK_MASTER_LOG && \
touch $SPARK_WORKER_LOG && \
ln -sf /dev/stdout $SPARK_MASTER_LOG && \
ln -sf /dev/stdout $SPARK_WORKER_LOG

WORKDIR $LIVY_HOME

RUN mkdir -p "${LIVY_HOME}/logs"

COPY /infra/recipes/docker-compose/common/spark/start-spark.sh /
COPY /build/openhouse-spark-3.5-runtime_2.12/libs/openhouse-spark-3.5-runtime_2.12-uber.jar $SPARK_HOME/openhouse-spark-runtime_2.12-latest-all.jar
COPY /build/openhouse-spark-apps-1.5_2.12/libs/openhouse-spark-apps-1.5_2.12-uber.jar $SPARK_HOME/openhouse-spark-apps_2.12-latest-all.jar
COPY /build/dummytokens/libs/dummytokens*.jar /dummytokens.jar
RUN java -jar /dummytokens.jar -d /var/config/

ARG OH_USERNAME=openhouse
ARG OH_GROUPNAME=$OH_USERNAME
ARG OH_USER_ID=1000
ARG OH_GROUP_ID=$OH_USER_ID
ENV OH_USER_HOME=/home/$OH_USERNAME

# Create an openhouse user as there's no reason to run as root user
RUN groupadd --force -g $OH_GROUP_ID $OH_USERNAME
RUN useradd -d $OH_USER_HOME -m $OH_USERNAME -u $OH_USER_ID -g $OH_GROUP_ID

RUN chown -R $OH_USERNAME:$OH_GROUPNAME /opt

ARG TABLE_OWNER_USERNAME=u_tableowner
ARG TABLE_OWNER_GROUPNAME=$TABLE_OWNER_USERNAME
ARG TABLE_OWNER_USER_ID=1001
ARG TABLE_OWNER_GROUP_ID=$TABLE_OWNER_USER_ID
ENV TABLE_OWNER_USER_HOME=/home/$TABLE_OWNER_USERNAME

# Create an openhouse user as there's no reason to run as root user
RUN groupadd --force -g $TABLE_OWNER_GROUP_ID $TABLE_OWNER_USERNAME
RUN useradd -d $TABLE_OWNER_USER_HOME -m $TABLE_OWNER_USERNAME -u $TABLE_OWNER_USER_ID -g $TABLE_OWNER_GROUP_ID


USER $OH_USERNAME

WORKDIR /opt/spark

CMD ["/bin/bash", "/start-spark.sh"]
