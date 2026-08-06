#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# It's recommended to use ./bin/docker-image-tool.sh to build the image rather than the vanilla docker command.

# Usage (use vanilla docker command):
#   1. use ./build/dist to make binary tarball of Kyuubi or download an official binary tarball
#   2. untar it and run the docker command below
#      docker build -f docker/Dockerfile -t <private-repo>/kyuubi:<tag> .

ARG BASE_IMAGE=eclipse-temurin:8-jdk-focal
ARG spark_provided="spark_builtin"

FROM ${BASE_IMAGE} as builder_spark_provided
ONBUILD ARG spark_home_in_docker
ONBUILD ENV SPARK_HOME ${spark_home_in_docker}

FROM ${BASE_IMAGE} as builder_spark_builtin

ONBUILD ENV SPARK_HOME /opt/spark
ONBUILD RUN mkdir -p ${SPARK_HOME}
ONBUILD COPY spark-binary ${SPARK_HOME}

FROM builder_${spark_provided}

ARG kyuubi_uid=10009
USER root

ENV KYUUBI_HOME=/opt/kyuubi
ENV KYUUBI_LOG_DIR=${KYUUBI_HOME}/logs
ENV KYUUBI_PID_DIR=${KYUUBI_HOME}/pid
ENV KYUUBI_WORK_DIR_ROOT=${KYUUBI_HOME}/work

RUN set -ex && \
    sed -i 's/http:\/\/deb.\(.*\)/https:\/\/deb.\1/g' /etc/apt/sources.list && \
    apt-get update && \
    apt-get install -y bash tini libc6 libpam-modules krb5-user libnss3 procps && \
    apt-get install -y --no-install-recommends python3 python3-pip python3-venv && \
    ln -snf /bin/bash /bin/sh && \
    useradd -u ${kyuubi_uid} -g root kyuubi -d /home/kyuubi -m && \
    mkdir -p ${KYUUBI_HOME} ${KYUUBI_LOG_DIR} ${KYUUBI_PID_DIR} ${KYUUBI_WORK_DIR_ROOT} && \
    rm -rf /var/cache/apt/*

# Packages every notebook environment can see without downloading. Installed into the system
# interpreter, which notebook environments reach through --system-site-packages.
COPY docker/requirements.txt /tmp/notebook-requirements.txt
RUN pip3 install --no-cache-dir -r /tmp/notebook-requirements.txt && \
    rm -f /tmp/notebook-requirements.txt

COPY LICENSE ${KYUUBI_HOME}/
COPY bin ${KYUUBI_HOME}/bin
COPY jars ${KYUUBI_HOME}/jars
COPY beeline-jars ${KYUUBI_HOME}/beeline-jars
COPY web-ui ${KYUUBI_HOME}/web-ui
COPY externals/engines/spark ${KYUUBI_HOME}/externals/engines/spark

WORKDIR ${KYUUBI_HOME}
RUN chown -R kyuubi:root ${KYUUBI_HOME} && \
    chmod ug+rw -R ${KYUUBI_HOME} && \
    chmod a+rwx -R ${KYUUBI_WORK_DIR_ROOT}

CMD [ "./bin/kyuubi", "run" ]

USER ${kyuubi_uid}
