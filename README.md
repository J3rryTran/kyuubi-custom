# kyuubi-custom
fork from https://github.com/apache/kyuubi.git

version 1.10.3

## Build
- Run first:

> build/mvn clean install -DskipTests

- Then, buil 2 option:
  - Build binary:

> ./build/dist --tgz --web-ui --spark-provided --flink-provided --hive-provided

  - Build binary with specific version   
> ./build/dist --tgz --web-ui --spark-provided --flink-provided --hive-provided \                                                                                                                                                   
    -Dspark.version=<version_spark> \
    -Dflink.version=<version_flink> \ 
    -Dhive.version=<version_hive>

  - Build module:

> build/mvn clean install -pl <module> -am -DskipTests -Dspotless.check.skip=true

  - Push to dockerhub:
> bin/docker-image-tool.sh -r <name_account> -i <name_repo_dockerhub> -t <tag> push

### The list of module:
- Server: kyuubi-server
- OIDC: kyuubi-oidc-auth
- JDBC: kyuubi-hive-jdbc
- SQL engine: externals/kyuubi-spark-sql-engin

Location of the JAR library file after the build process: <module>/target/<artifact>-1.10.3.jar
