@echo off
setlocal
rem Gradle Wrapper launcher. Requires JDK 17.
set "APP_HOME=%~dp0"
if defined JAVA_HOME goto findJavaFromJavaHome
set "JAVA_EXE=java.exe"
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% EQU 0 goto execute
echo Java 17 is required. Set JAVA_HOME to your JDK 17 installation. 1>&2
exit /b 1

:findJavaFromJavaHome
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_EXE%" goto execute
echo JAVA_HOME does not point to a valid Java installation. 1>&2
exit /b 1

:execute
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%