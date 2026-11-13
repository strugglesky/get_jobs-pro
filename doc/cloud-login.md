# 云服务器后台登录

新增登录只保护 Get Jobs 管理后台。Boss、猎聘、51job 和智联的扫码、Playwright 浏览器上下文、数据库中的平台 Cookie 以及投递通知机器人均保持独立；退出后台不会注销招聘平台，也不会停止已经启动的投递任务。重新登录后台后可以查看原有任务状态。

## 配置

在应用运行目录的 `.env` 中填写以下配置，随后重启后端。系统环境变量优先于 `.env`。不要把 `.env` 上传到 Git、前端静态目录或公开下载目录。

```dotenv
LOGIN_WECOM_WEBHOOK=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=你的独立登录机器人key
LOGIN_ALLOWED_ORIGINS=http://localhost:6866,http://127.0.0.1:6866
LOGIN_COOKIE_SECURE=false
```

- `LOGIN_WECOM_WEBHOOK`：专门接收后台登录验证码的机器人；不读取数据库中的投递通知 `HOOK_URL`，也不受 `BOT_IS_SEND` 控制。请使用只有本人可读取的企业微信群，群内任何能读取验证码的人都可能登录。
- `LOGIN_ALLOWED_ORIGINS`：允许跨域访问 API 的前端完整 origin，逗号分隔，不带路径或末尾斜线，不使用 `*`。同源部署不需要额外添加域名；前后端分离时填写真实前端 origin。
- `LOGIN_COOKIE_SECURE`：本地 HTTP 调试为 `false`，公网 HTTPS 部署必须改为 `true`。

未配置机器人时，页面会给出提示，所有业务 API 仍然需要认证，不会降级为免登录。验证码和会话保存在单个后端进程内，重启后需要重新登录；此实现面向个人单实例，不支持多实例共享会话。

## 使用

1. 打开后台任意页面，未登录时自动进入 `/login`。
2. 点击“获取验证码”，在企业微信中查看六位数字，并在**同一个浏览器**输入。
3. 验证成功后回到原来访问的页面。平台扫码、保存 Cookie 等操作与原来一致。
4. 侧栏“退出后台登录”只退出管理后台，不影响平台账号。

验证码有效期 5 分钟、一次性使用、绑定请求浏览器；同一验证码最多尝试 5 次。全局每 60 秒最多发送一次、每小时最多 20 次（发送失败也计入），10 分钟内最多 20 次验证失败。登录后更换会话 ID，Cookie 为 HttpOnly、SameSite=Strict，登录会话最长 8 小时。验证码、机器人 URL 不返回给前端或写入应用日志。

公网访问建议额外在反向代理设置访问 IP 白名单或 VPN 以及请求限速，避免陌生人反复触发验证码或耗尽全局限额。这些限制是单进程内存状态，重启会重置。

## 云部署建议

生产前端默认使用同源相对 API 路径，避免把 `localhost:9529` 编译到访问者浏览器中。开发模式默认请求当前主机的 9529 端口；需要覆盖时，在构建前设置 `NEXT_PUBLIC_API_BASE`，或修改 `front/server.config.js` 的 `api.baseUrl` 后重建。跨域部署必须同站点（例如同一主域的子域），否则 Strict Cookie 不会被发送，推荐始终使用同源反向代理。

先构建前端，再构建包含静态页面的后端：

```bash
cd front
pnpm install --frozen-lockfile
pnpm run build:prod
cd ..
./gradlew bootJar
```

把 JAR、数据库和所需运行数据部署到服务器；将 `.env` 放在启动工作目录。后端通过系统环境变量 `SERVER_ADDRESS=127.0.0.1` 只监听回环地址，防火墙不开放 9529。使用 Nginx 的 HTTPS 域名统一代理整个应用，在已配置证书的 `server` 中加入：

```nginx
location / {
    proxy_pass http://127.0.0.1:9529;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_buffering off;
    proxy_read_timeout 3600s;
}
```

HTTP 应跳转到 HTTPS；上述配置不包含证书配置。SSE 使用携带会话 Cookie 的 EventSource，禁用代理缓冲以便及时接收平台二维码、登录状态及投递进度。现有浏览器任务需要服务器安装 Playwright 所需浏览器和系统依赖；如果继续使用有界面浏览器，Linux 需要可用的显示环境/虚拟显示。这部分不是后台验证码登录提供的功能。

静态前端页面本身不包含用户数据；所有业务 API 和 Actuator 均受到后端会话保护。不要直接暴露数据库、运行日志、浏览器用户数据和其他工作目录。

## API 约定

| 接口 | 请求 | 成功结果 |
| --- | --- | --- |
| `GET /api/auth/session` | 无需认证 | `authenticated`、`configured`，不创建匿名会话 |
| `POST /api/auth/code` | `X-Requested-With: GetJobs` | `message`、`retryAfter: 60`、`expiresIn: 300`；Set-Cookie |
| `POST /api/auth/login` | 同一会话 Cookie，安全校验头，JSON `{"code":"六位数字"}` | `authenticated: true`；轮换会话 Cookie |
| `POST /api/auth/logout` | 会话 Cookie，安全校验头 | `204`，仅销毁后台会话 |

业务 API 和 Actuator 未认证返回 `401`；非安全方法缺少校验头或跨域来源不可信返回 `403`；无效验证码 `400`；限流 `429` 并附 `Retry-After`；未配置机器人 `503`；发送失败 `502`。错误 JSON 提供 `message`，验证码限流另提供 `retryAfter`。调用 API 的脚本也需要先登录、保存 Cookie，并在写请求中带上安全校验头。

## 验证

```powershell
postman simulation run postman/simulations/wecom.sim.yaml --port 45123 --no-history --output ndjson
```

另开终端，在项目根目录运行：

```powershell
$env:WECOM_SIMULATION_URL = 'http://127.0.0.1:45123/wecom'
.\gradlew.bat test --tests 'com.getjobs.application.auth.*'
Remove-Item Env:WECOM_SIMULATION_URL
```

测试覆盖一次性与过期验证码、浏览器绑定、发送/验证限流、会话轮换与 Cookie 属性、跨域与请求头保护、四个平台的 Cookie 保存接口，以及后台退出不调用招聘平台 Cookie/浏览器服务。模拟测试通过真实 HTTP 客户端验证成功、平台限流、无效 key、HTTP 503、非 JSON 与超时，但不证明真实机器人或真实平台扫码已验证。
