@echo off
set "ROOT=%~dp0"
if not exist "%ROOT%gradle\wrapper\gradle-wrapper.jar" (
 echo Missing gradle-wrapper.jar. See BUILD_ANDROID.md
 exit /b 2
)
java -classpath "%ROOT%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
