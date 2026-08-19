@echo off
setlocal
set "MAVEN_PROJECTBASEDIR=%~dp0"
if not defined JAVA_HOME goto noJavaHome
set "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
if exist "%WRAPPER_JAR%" goto runMaven
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar' -OutFile '%WRAPPER_JAR%'"
if errorlevel 1 exit /b 1
:runMaven
"%JAVA_HOME%\bin\java.exe" -classpath "%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar" "org.apache.maven.wrapper.MavenWrapperMain" %*
goto end
:noJavaHome
echo JAVA_HOME must point to a Java 21 JDK. 1>&2
exit /b 1
:end
endlocal
