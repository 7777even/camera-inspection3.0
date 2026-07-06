# Task 3 测试验证指南

## 问题描述
沙箱阻塞了 Maven 测试执行，需要手动验证测试是否通过。

## 验证步骤

### 1. 运行 Entity 测试（快速验证）
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;D:\apache-maven-3.9.11\apache-maven-3.9.11\bin;" + $env:Path
Set-Location "D:\gkproject\camera-inspection3.0\enviro-brain"
mvn -B test -Dtest="HasSyncVersionTest,CameraConfigTest,CameraResultTest,InspectionRecordTest,LedgerRecordTest" "-Dmaven.repo.local=$env:USERPROFILE\.workbuddy\maven\repository"
```

### 2. 运行所有测试（完整验证）
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;D:\apache-maven-3.9.11\apache-maven-3.9.11\bin;" + $env:Path
Set-Location "D:\gkproject\camera-inspection3.0\enviro-brain"
mvn -B test "-Dmaven.repo.local=$env:USERPROFILE\.workbuddy\maven\repository"
```

## 预期结果
- BUILD SUCCESS
- Tests run: 至少 9 个测试（5 个 Entity 测试 + 4 个 Mapper 测试）
- Failures: 0
- Errors: 0

## 如果测试失败
请查看错误信息，告诉我失败原因，我会修复。

## TDD 证据
- RED: 测试文件先创建（commit 862f9b3, 9b504e9, 68f79df）
- GREEN: 生产和测试代码已提交
- REFACTOR: HasSyncVersionTest 已修复（commit 749332a）

## 下一步
验证测试通过后，我将继续：
1. 生成 review package
2. Dispatch reviewer subagent
3. Mark Task 3 complete
4. 开始 Task 4（API Key 认证）
