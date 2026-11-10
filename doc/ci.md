# 持续集成（CI）

工作流配置位于 `.github/workflows/ci.yml`。分支推送、Pull Request 和 GitHub Actions 页面的手动运行都会触发检查，适用于 `dev`、`main` 及功能分支。同一事件和分支的新运行会取消尚未完成的旧运行。

## 执行流程

| 作业 | 环境 | 检查内容 |
| --- | --- | --- |
| Frontend checks and build | Ubuntu 24.04、Node.js 24、pnpm 10.33.1 | 锁文件安装依赖、ESLint 报告、Next.js 路由类型生成、TypeScript 检查、前端生产构建 |
| Backend tests and application package | Ubuntu 24.04、Temurin JDK 21、仓库 Gradle Wrapper | 下载前端产物、启动本地企业微信 mock、启用浏览器 DOM 测试、后端测试、Spring Boot JAR 打包及前端入包检查 |

后端作业依赖前端作业成功，复用已构建的页面，不重复安装前端依赖。类型检查、前端构建、后端测试和打包失败时，流水线会失败。

pnpm 版本通过 `front/package.json` 的 `packageManager` 固定，安装使用 `--frozen-lockfile`，防止 CI 自动更新依赖。流水线缓存 pnpm store 和 Gradle 依赖；Pull Request 的 Gradle 缓存只读。

`gradle.properties` 中的代理只适用于本地工作站。Gradle Wrapper 会在命令行参数之后读取该文件，因此 CI 先在 Runner 的临时检出目录中移除 HTTP/HTTPS 代理主机和端口配置，再执行 Wrapper；不会修改开发者工作区。Linux 使用 `bash ./gradlew`，不依赖仓库中的可执行权限标记。

## ESLint 的现有问题

当前全量 ESLint 存在历史错误。因此，ESLint 步骤明确设为 advisory：产生告警并上传 JSON 报告，但暂不阻止构建。TypeScript 和生产构建仍是强制检查。修复历史问题后，可移除该步骤的 `continue-on-error: true`，让 ESLint 也成为合并门槛。

## 本地依赖模拟与浏览器测试

CI 启用 `BOSS_CHAT_DOM_TEST=true`，通过 `BOSS_TEST_CHROME` 指向 Runner 上的 Chrome。DOM 测试使用路由拦截的测试网页。

企业微信测试直接运行已有的 `postman/mocks/wecom/default.js`，并设置仅供测试进程使用的 `WECOM_SIMULATION_URL`。它复用已有 Postman 模拟的处理逻辑，在 CI 中以 Node.js HTTP 服务启动，不安装 Postman CLI，也不需要 `POSTMAN_API_KEY` 或真实机器人 Webhook。

就绪检查通过后才运行测试，作业结束时停止 mock。测试覆盖成功响应、限流、无效 key、HTTP 503、非 JSON、超时，以及汇总上传和附件发送顺序。这些结果证明本项目如何处理模拟响应，不证明真实企业微信群的权限或消息送达。

## 构建产物与报告

在一次运行的 GitHub Actions 页面底部，可以下载以下 artifacts，保留 7 天：

- `frontend-dist`：用于后端打包的前端静态页面。
- `frontend-lint`：ESLint JSON 报告。
- `get-jobs-application`：包含前端页面的 Spring Boot JAR，仅在构建成功时上传。
- `backend-test-reports`：Gradle HTML/JUnit 测试报告和本地 mock 启动日志，测试失败时也上传。

该流水线负责校验和打包，不自动部署应用。

## 本地复现

在项目根目录安装依赖并运行前端检查：

```powershell
pnpm --dir front install --frozen-lockfile
pnpm --dir front exec next typegen
pnpm --dir front exec tsc --noEmit
pnpm --dir front run build:prod
.\gradlew.bat test bootJar
```

最后一条命令默认运行普通测试。要包含企业微信模拟测试，另开终端启动已有 mock，再设置 `WECOM_SIMULATION_URL`；浏览器 DOM 测试需要设置 `BOSS_CHAT_DOM_TEST=true` 和本机 Chrome 的 `BOSS_TEST_CHROME`。修改这些环境变量后，使用 `--rerun-tasks` 强制重跑，避免复用未启用集成测试时的 Gradle 测试结果。

项目原有 Postman CLI 模拟方式见 `postman/simulations/README.md`，该方式的测试地址带 `/wecom` 路径；直接启动 Node.js mock 时不带这个路径。

如需启用合并保护，可在 GitHub 仓库的分支规则中将本工作流的两个作业，以及已有的 `Commit Message Lint` 检查设为必需。工作流文件本身不会修改仓库分支规则。
