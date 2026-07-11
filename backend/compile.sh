#!/bin/bash
# 手动编译 backend/src/main/java -> target/classes
# 原因：本机 mvn 损坏（classworlds 缺失），改用 git-bash 手动拼 classpath + javac
# classpath 与 run-backend.sh 保持一致（含 spring-websocket / spring-messaging）
set -e
cd "$(dirname "$0")"
M2="C:/Users/huanghao/.m2/repository"
CLASSES="D:/Code/aaaSemesterTraining-III/SemesterTraining-III/backend/target/classes"
RESOURCES="D:/Code/aaaSemesterTraining-III/SemesterTraining-III/backend/src/main/resources"
JAVABIN="/c/Program Files/Microsoft/jdk-17.0.18.8-hotspot/bin"

SPRING_CORE=$(find "$M2/org/springframework/spring-core" "$M2/org/springframework/spring-beans" "$M2/org/springframework/spring-context" "$M2/org/springframework/spring-aop" "$M2/org/springframework/spring-expression" "$M2/org/springframework/spring-jcl" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
SPRING_WEB=$(find "$M2/org/springframework/spring-web" "$M2/org/springframework/spring-webmvc" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
SPRING_WS=$(find "$M2/org/springframework/spring-websocket" "$M2/org/springframework/spring-messaging" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
SPRING_BOOT=$(find "$M2/org/springframework/boot/spring-boot" "$M2/org/springframework/boot/spring-boot-starter" "$M2/org/springframework/boot/spring-boot-starter-web" "$M2/org/springframework/boot/spring-boot-starter-tomcat" "$M2/org/springframework/boot/spring-boot-starter-json" "$M2/org/springframework/boot/spring-boot-starter-logging" "$M2/org/springframework/boot/spring-boot-autoconfigure" "$M2/org/springframework/boot/spring-boot-actuator" "$M2/org/springframework/boot/spring-boot-actuator-autoconfigure" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
JACKSON=$(find "$M2/com/fasterxml/jackson" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
LOGBACK=$(find "$M2/ch/qos/logback" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
SLF4J="$M2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"
LOG4J_BRIDGE=$(find "$M2/org/apache/logging/log4j" -name 'log4j-to-slf4j-*.jar' ! -name '*sources*' 2>/dev/null | head -1)
JAKARTA=$(find "$M2/jakarta" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
TOMCAT=$(find "$M2/org/apache/tomcat" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
YAML=$(find "$M2/org/yaml" -name '*.jar' 2>/dev/null | tr '\n' ';')
MICROMETER=$(find "$M2/io/micrometer/micrometer-observation" "$M2/io/micrometer/micrometer-commons" "$M2/io/micrometer/context-propagation" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

CP="$CLASSES;$RESOURCES;$SPRING_CORE;$SPRING_WEB;$SPRING_WS;$SPRING_BOOT;$JACKSON;$LOGBACK;$SLF4J;$LOG4J_BRIDGE;$JAKARTA;$TOMCAT;$YAML;$MICROMETER"

mkdir -p "$CLASSES"
WINPWD=$(pwd -W)
SRCS="$WINPWD/target/srcs.txt"
find src/main/java -name '*.java' > "$SRCS"
echo "编译源文件数: $(wc -l < "$SRCS")"
"$JAVABIN/javac" -parameters -encoding UTF-8 -d "$CLASSES" -cp "$CP" "@$SRCS"
echo "COMPILE_OK"
