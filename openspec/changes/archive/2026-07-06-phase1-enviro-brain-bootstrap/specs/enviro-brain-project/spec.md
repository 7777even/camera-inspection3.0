## ADDED Requirements

### Requirement: Spring Boot 项目骨架

系统 SHALL 提供可编译运行的 Spring Boot 3.3.x Maven 项目，位于 `enviro-brain/` 目录，使用 Java 17。

#### Scenario: 项目结构完整
- **WHEN** 开发者在 `enviro-brain/` 目录执行 `mvn clean compile`
- **THEN** 编译成功，无错误，target 目录生成 class 文件

#### Scenario: 项目结构完整
- **WHEN** 开发者执行 `mvn spring-boot:run`
- **THEN** 应用启动成功，监听 8080 端口，控制台输出 Spring Boot banner

### Requirement: Maven 依赖管理

`pom.xml` SHALL 引入以下依赖：spring-boot-starter-web、mybatis-spring-boot-starter、mysql-connector-j、poi-ooxml、lombok、spring-boot-starter-test。

#### Scenario: 依赖版本检查
- **WHEN** 执行 `mvn dependency:tree`
- **THEN** 输出中包含上述所有依赖，无版本冲突

### Requirement: 分层配置

系统 SHALL 使用 `application.yml` 作为主配置，`application-dev.yml` 作为开发环境配置（数据库连接、API Key 等敏感信息），主配置通过 `spring.profiles.active: dev` 激活开发环境。

#### Scenario: 开发环境配置加载
- **WHEN** 应用以默认 profile 启动
- **THEN** 加载 `application-dev.yml` 中的数据库连接信息，日志输出 `active profile: dev`

### Requirement: 健康检查端点

系统 SHALL 提供 `GET /actuator/health` 端点，返回 `{ "status": "UP" }`，不受 API Key 认证拦截。

#### Scenario: 健康检查通过
- **WHEN** 客户端发送 `GET /actuator/health` 不带 API Key
- **THEN** 返回 HTTP 200，响应体 `{ "status": "UP" }`
