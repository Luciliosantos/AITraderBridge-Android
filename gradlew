#!/bin/sh
# Gradle Wrapper launcher. The wrapper JAR and version are in gradle/wrapper/.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
    echo "Java 17 is required. Set JAVA_HOME to your JDK 17 installation." >&2
    exit 1
fi

# JAVA_OPTS and GRADLE_OPTS intentionally allow multiple JVM arguments.
exec "$JAVACMD" $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=gradlew" \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"