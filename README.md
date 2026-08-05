# kyuubi-custom
fork from https://github.com/apache/kyuubi.git

version 1.10.3

## Build and publish

Run the following steps from the project root.

### 1. Build the project

```bash
./build/mvn clean install -DskipTests
```

To build only a specific module and its dependencies:

```bash
./build/mvn clean install \
  -pl <module> -am \
  -DskipTests \
  -Dspotless.check.skip=true
```

### 2. Create a binary package

Create a runnable binary package that includes the Kyuubi Web UI and uses external Spark, Flink, and Hive installations:

```bash
./build/dist \
  --name <custom-name-option> \
  --tgz \
  --web-ui \
  --spark-provided \
  --flink-provided \
  --hive-provided
```

The generated archive has a name similar to:

```text
apache-kyuubi-1.10.3-bin-<custom-name-option>.tgz
```

Extract the archive and use the packaged `bin/docker-image-tool.sh` for the Docker steps:

```bash
tar -xzf apache-kyuubi-1.10.3-bin-<custom-name-option>.tgz
cd apache-kyuubi-1.10.3-bin-<custom-name-option>
```

### 3. Build the Docker image

The following command creates:

```text
<name_repo_docker>/kyuubi-custom:<tag>
```

```bash
./bin/docker-image-tool.sh \
  -r <name_repo_docker> \
  -i kyuubi-custom \
  -t <tag> \
  -s /opt/spark \
  -b BASE_IMAGE=eclipse-temurin:17-jdk-focal \
  build
```

Use `-s` when Spark is installed locally and should be copied into the image. If the base image already contains Spark at `/opt/spark`, use `-S /opt/spark`:

```bash
./bin/docker-image-tool.sh \
  -r <name_repo_docker> \
  -i kyuubi-custom \
  -t <tag> \
  -S /opt/spark \
  -b BASE_IMAGE=eclipse-temurin:17-jdk-focal \
  build
```

### 4. Push the Docker image

Log in to Docker Hub and push the image:

```bash
docker login

./bin/docker-image-tool.sh \
  -r <name_repo_docker> \
  -i kyuubi-custom \
  -t <tag> \
  push
```

### Docker image options

The Docker image tool builds the image name using the following format:

```text
<repository>/<image-name>:<tag>
```

Options:

- `-r`: Docker repository or registry namespace. For Docker Hub, use `docker.io/<username>`.
- `-i`: Docker image name. Defaults to `kyuubi` if omitted.
- `-t`: Docker image tag.
- `-b KEY=VALUE`: Docker build argument. This option can be specified multiple times.
- `-s <path>`: Copy a local Spark installation into the image and use it as `SPARK_HOME`.
- `-S <path>`: Declare the Spark installation path inside the image without copying Spark. The base image must already contain Spark at this path.
- `-n`: Build the image without using the Docker build cache.
- `-X`: Build and push a multi-platform image using Docker Buildx.

For example, the following command creates:

```text
docker.io/trungtmba11093/kyuubi-custom:1.10.3-custom_version
```

```bash
./bin/docker-image-tool.sh \
  -r docker.io/trungtmba11093 \
  -i kyuubi-custom \
  -t 1.10.3-custom_version \
  -s /path/to/local/spark \
  -b BASE_IMAGE=eclipse-temurin:17-jdk-focal \
  build
```

Use `-S` only when the base image already contains Spark. For example, if Spark is installed at `/opt/spark` in the base image:

```bash
./bin/docker-image-tool.sh \
  -r docker.io/trungtmba11093 \
  -i kyuubi-custom \
  -t 1.10.3-custom_version \
  -S /opt/spark \
  -b BASE_IMAGE=<image-that-already-contains-spark> \
  build
```

After logging in with `docker login`, push the image with:

```bash
./bin/docker-image-tool.sh \
  -r docker.io/trungtmba11093 \
  -i kyuubi-custom \
  -t 1.10.3-custom_version \
  push
```

### The list of module added:
- OIDC: kyuubi-oidc-auth
- Ranger Author

Location of the JAR library file after the build process: <module>/target/<artifact>-1.10.3.jar
