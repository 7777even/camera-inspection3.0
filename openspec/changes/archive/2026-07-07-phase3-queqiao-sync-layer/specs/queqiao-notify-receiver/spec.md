# Capability: queqiao-notify-receiver

可选的环保小脑回调接收端点，受 API Key 保护，收到通知后立即触发增量同步。

## ADDED Requirements

### Requirement: 回调接收端点
鹊桥 SHALL 暴露 `POST /api/notify/new-data` 接收环保小脑回调，请求体为 `{"syncVersion": long, "type": string}`。

#### Scenario: 接收合法通知
- **WHEN** 环保小脑 POST `{"syncVersion":1709280001234,"type":"inspection_completed"}`
- **THEN** 端点返回 2xx 并立即触发一轮增量同步

### Requirement: 回调接口鉴权
该端点 SHALL 校验请求头 `X-API-Key`，值不匹配时返回 401 且不触发同步。

#### Scenario: 错误 Key 被拒绝
- **WHEN** 请求携带错误的 `X-API-Key`
- **THEN** 返回 401 且本轮同步未被触发

### Requirement: 回调失败隔离
回调处理（触发同步）异常 SHALL 不向外抛 500，仅记日志，保证回调端健壮。

#### Scenario: 同步失败不影响回调返回
- **WHEN** 回调触发的同步因环保小脑不可达失败
- **THEN** 端点仍返回 2xx（或已接受），异常被记录日志，不向上抛出
