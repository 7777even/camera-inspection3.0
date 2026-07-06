$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;D:\apache-maven-3.9.11\apache-maven-3.9.11\bin;" + $env:Path
Set-Location "D:\gkproject\camera-inspection3.0\enviro-brain"
mvn -B clean test-compile "-Dmaven.repo.local=$env:USERPROFILE\.workbuddy\maven\repository"
