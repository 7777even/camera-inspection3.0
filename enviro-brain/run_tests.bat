@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-17
set M2_HOME=D:\apache-maven-3.9.11\apache-maven-3.9.11
set PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;%PATH%
cd /d D:\gkproject\camera-inspection3.0\enviro-brain
call mvn.cmd -B clean test "-Dmaven.repo.local=%USERPROFILE%\.workbuddy\maven\repository"
echo EXIT_CODE=%ERRORLEVEL%
