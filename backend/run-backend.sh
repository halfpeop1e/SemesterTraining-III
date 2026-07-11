#!/bin/bash
# Build classpath and launch Spring Boot backend
# Only include specific Spring framework jars (not spring-ai, not reactive)
M2="C:/Users/huanghao/.m2/repository"
CLASSES="D:/Code/aaaSemesterTraining-III/SemesterTraining-III/backend/target/classes"
RESOURCES="D:/Code/aaaSemesterTraining-III/SemesterTraining-III/backend/src/main/resources"
SCRIPT_DIR="D:/Code/aaaSemesterTraining-III/SemesterTraining-III/backend"
ARGFILE="$SCRIPT_DIR/cp-argfile.txt"

# Spring framework core jars (exclude ai, r2dbc, webflux, etc.)
SPRING_CORE=$(find \
  "$M2/org/springframework/spring-core" \
  "$M2/org/springframework/spring-beans" \
  "$M2/org/springframework/spring-context" \
  "$M2/org/springframework/spring-aop" \
  "$M2/org/springframework/spring-expression" \
  "$M2/org/springframework/spring-jcl" \
  -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

# Spring web (servlet, not reactive)
SPRING_WEB=$(find \
  "$M2/org/springframework/spring-web" \
  "$M2/org/springframework/spring-webmvc" \
  -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

# Spring WebSocket + Messaging (应用使用 WebSocket 推送仿真状态)
SPRING_WS=$(find \
  "$M2/org/springframework/spring-websocket" \
  "$M2/org/springframework/spring-messaging" \
  -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

# Spring Boot (only needed ones, exclude maven-plugin, buildpack, loader-tools, devtools, reactive)
SPRING_BOOT=$(find \
  "$M2/org/springframework/boot/spring-boot" \
  "$M2/org/springframework/boot/spring-boot-starter" \
  "$M2/org/springframework/boot/spring-boot-starter-web" \
  "$M2/org/springframework/boot/spring-boot-starter-tomcat" \
  "$M2/org/springframework/boot/spring-boot-starter-json" \
  "$M2/org/springframework/boot/spring-boot-starter-logging" \
  "$M2/org/springframework/boot/spring-boot-autoconfigure" \
  "$M2/org/springframework/boot/spring-boot-actuator" \
  "$M2/org/springframework/boot/spring-boot-actuator-autoconfigure" \
  -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

# Jackson
JACKSON=$(find "$M2/com/fasterxml/jackson" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

# Logback + SLF4J (only 2.0.17)
LOGBACK=$(find "$M2/ch/qos/logback" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')
SLF4J="$M2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"
LOG4J_BRIDGE=$(find "$M2/org/apache/logging/log4j" -name 'log4j-to-slf4j-*.jar' ! -name '*sources*' 2>/dev/null | head -1)

# Jakarta
JAKARTA=$(find "$M2/jakarta" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

# Tomcat embedded
TOMCAT=$(find "$M2/org/apache/tomcat" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

# SnakeYAML
YAML=$(find "$M2/org/yaml" -name '*.jar' 2>/dev/null | tr '\n' ';')

# Micrometer (only core + observation, not full)
MICROMETER=$(find \
  "$M2/io/micrometer/micrometer-observation" \
  "$M2/io/micrometer/micrometer-commons" \
  "$M2/io/micrometer/context-propagation" \
  -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ';')

CP="$CLASSES;$RESOURCES;$SPRING_CORE;$SPRING_WEB;$SPRING_WS;$SPRING_BOOT;$JACKSON;$LOGBACK;$SLF4J;$LOG4J_BRIDGE;$JAKARTA;$TOMCAT;$YAML;$MICROMETER"

echo -n "$CP" > "$ARGFILE"
echo "Classpath entries: $(echo "$CP" | tr ';' '\n' | wc -l)"

JAVA="/c/Program Files/Microsoft/jdk-17.0.18.8-hotspot/bin/java"
exec "$JAVA" -cp @"$ARGFILE" \
  -Dserver.port=8080 \
  com.bjtu.railtransit.RailTransitApplication
