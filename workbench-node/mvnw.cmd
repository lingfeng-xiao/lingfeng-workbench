@echo off
setlocal
set "MAVEN_PROJECTBASEDIR=%~dp0"
if not defined JAVA_HOME goto noJavaHome
if not exist "%JAVA_HOME%\bin\java.exe" goto invalidJavaHome

set "WRAPPER_DIR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper"
set "WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar"
if exist "%WRAPPER_JAR%" goto runWrapper

for /F "usebackq tokens=1,2 delims==" %%A in ("%WRAPPER_DIR%\maven-wrapper.properties") do (
    if "%%A"=="wrapperUrl" set "WRAPPER_URL=%%B"
)
if not defined WRAPPER_URL goto missingWrapperUrl
powershell -NoProfile -NonInteractive -Command ^
  "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; " ^
  "(New-Object Net.WebClient).DownloadFile('%WRAPPER_URL%', '%WRAPPER_JAR%')"
if errorlevel 1 goto wrapperDownloadFailed

:runWrapper
"%JAVA_HOME%\bin\java.exe" -classpath "%WRAPPER_JAR%" ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %ERRORLEVEL%

:noJavaHome
echo JAVA_HOME must point to a Java 21 JDK. 1>&2
exit /b 1

:invalidJavaHome
echo JAVA_HOME does not contain bin\java.exe. 1>&2
exit /b 1

:missingWrapperUrl
echo wrapperUrl is missing from maven-wrapper.properties. 1>&2
exit /b 1

:wrapperDownloadFailed
echo Maven Wrapper download failed. 1>&2
exit /b 1
