@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;D:\apache-maven-3.9.11\apache-maven-3.9.11\bin;%PATH%
cd /d D:\gkproject\camera-inspection3.0\enviro-brain
mvn -B clean test-compile "-Dmaven.repo.local=%USERPROFILE%\.workbuddy\maven\repository"
