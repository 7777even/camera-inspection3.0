## ADDED Requirements

### Requirement: API Key 拦截器

系统 SHALL 实现 `ApiKeyAuthInterceptor`，拦截所有 `/api/**` 路径请求，校验 `X-API-Key` 请求头与 `application.yml` 中 `enviro.api.key` 配置值是否一致。

#### Scenario: 合法请求通过
- **WHEN** 客户端发送 `GET /api/v1/sync/watermark` 附带 `X-API-Key: dev-api-key-2026`
- **THEN** 拦截器放行，请求到达 Controller

#### Scenario: 缺少 API Key
- **WHEN** 客户端发送 `GET /api/v1/sync/watermark` 不附带 X-API-Key 头
- **THEN** 返回 HTTP 401，响应体 `{ "code": 401, "message": "Missing X-API-Key header" }`

#### Scenario: API Key 不匹配
- **WHEN** 客户端发送 `GET /api/v1/sync/watermark` 附带 `X-API-Key: wrong-key`
- **THEN** 返回 HTTP 401，响应体 `{ "code": 401, "message": "Invalid API Key" }`

### Requirement: 拦截器白名单

系统 SHALL 对 `/actuator/health` 和 `/error` 路径跳过 API Key 校验。

#### Scenario: 健康检查免认证
- **WHEN** 客户端发送 `GET /actuator/health` 不带 X-API-Key
- **THEN** 请求不受拦截，正常返回 200

#### Scenario: 错误页面免认证
- **WHEN** 客户端请求不存在的路径 `/api/not-exist`
- **THEN** Spring 默认错误处理生效，不受拦截器阻断

### Requirement: WebMvcConfigurer 注册

系统 SHALL 在 `WebMvcConfig` 中实现 `addInterceptors`，将 `ApiKeyAuthInterceptor` 注册到 `/api/**` 路径模式。

#### Scenario: 拦截器生效
- **WHEN** 应用启动后，Spring MVC 拦截器链注册完成
- **THEN** 所有 `/api/**` 请求经过 ApiKeyAuthInterceptor
