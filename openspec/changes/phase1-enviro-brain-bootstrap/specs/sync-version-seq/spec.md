## ADDED Requirements

### Requirement: 原子获取下一个版本号

系统 SHALL 通过 `UPDATE sync_version_seq SET next_val = next_val + 1 WHERE id = 1; SELECT next_val FROM sync_version_seq WHERE id = 1;` 在同一事务中原子获取下一个全局递增版本号。

#### Scenario: 单线程递增
- **WHEN** 连续调用 nextVersion() 3 次
- **THEN** 返回值为 1、2、3，每次递增 1

#### Scenario: 并发安全
- **WHEN** 10 个线程同时调用 nextVersion()
- **THEN** 返回 10 个不重复的连续整数，无丢失无重复

### Requirement: MyBatis Mapper 方法

系统 SHALL 在 `SyncVersionMapper` 接口中提供 `long nextVersion()` 方法，调用上述 SQL。

#### Scenario: Mapper 注入可用
- **WHEN** Spring 容器启动完成
- **THEN** SyncVersionMapper bean 存在且可注入到 Service 层

### Requirement: Service 层封装

系统 SHALL 提供 `SyncVersionService` 类，封装 `nextVersion()` 调用，支持 Spring `@Transactional` 事务传播。

#### Scenario: Service 获取版本号
- **WHEN** 调用 `syncVersionService.nextVersion()`
- **THEN** 返回当前版本号 + 1，数据库中 next_val 已递增
