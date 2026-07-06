#!/bin/bash
# 设置环境变量
export JAVA_HOME="/d/jdk-17_windows-x64_bin/jdk-17.0.4.1"
export MAVEN_HOME="/d/apache-maven-3.9.11/apache-maven-3.9.11"
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"

# 进入项目目录
cd "/d/gkproject/camera-inspection3.0/enviro-brain"

# 运行测试
mvn -B clean test "-Dmaven.repo.local=$USERPROFILE/.workbuddy/maven/repository"
