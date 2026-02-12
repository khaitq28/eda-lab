#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF)
# Maven Wrapper script (simplified for bootstrapping)
# ----------------------------------------------------------------------------

# Attempt to set JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
  if [ -x "/usr/libexec/java_home" ]; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
  fi
fi

if [ -z "$JAVA_HOME" ]; then
  JAVACMD="java"
else
  JAVACMD="$JAVA_HOME/bin/java"
fi

# Define wrapper jar location
MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

# Download wrapper jar if not present
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven Wrapper..."
  if command -v curl > /dev/null 2>&1; then
    curl -sL -o "$WRAPPER_JAR" "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
  elif command -v wget > /dev/null 2>&1; then
    wget -q -O "$WRAPPER_JAR" "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
  else
    echo "Error: Neither curl nor wget found. Cannot download Maven Wrapper."
    echo "Please install curl or wget, or download maven-wrapper.jar manually."
    exit 1
  fi
fi

# Execute wrapper
exec "$JAVACMD" \
  $MAVEN_OPTS \
  -classpath "$WRAPPER_JAR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
