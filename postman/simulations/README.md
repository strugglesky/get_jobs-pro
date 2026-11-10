企业微信通知的本地验证使用 Postman CLI 的模拟服务，不读取数据库或真实机器人地址。

在项目根目录启动（Postman CLI 1.75.0 验证）：

```powershell
postman simulation run postman/simulations/wecom.sim.yaml --port 45123 --no-history --output ndjson
```

另开 PowerShell，设置测试专用地址并运行测试：

```powershell
$env:WECOM_SIMULATION_URL = 'http://127.0.0.1:45123/wecom'
.\gradlew.bat test --tests '*BotSimulationTest'
Remove-Item Env:WECOM_SIMULATION_URL
```

验证成功响应、企业微信限流错误、无效 key、HTTP 503、非 JSON 响应和 5 秒超时。测试运行真实 Java HTTP 客户端，检查成功日志、失败隔离和有限重试；模拟日志应出现对应 webhook 请求。生产定时器提供发送间隔，测试直接驱动队列以缩短用时。不设置该环境变量时，这六个模拟测试跳过，其他通知单元测试仍运行。

模拟接口按机器人文本 webhook 的请求/响应形状编写，未知路由返回 501；本次未搜索提供方发布的 mock（CLI 不提供技能文档所述的 `postman status` 命令）。通过模拟只证明本项目对这些响应的处理，不代表真实 key、群权限或实际平台投递已验证。正式收信可保存你的机器人配置后，通过正常投递验证。

停止模拟服务：回到运行窗口按 Ctrl+C。不要把测试模拟地址填写进应用的企业微信配置。
