# Cartography NeoForge MVP Bootstrap And Delivery Plan

## Summary
- 目标仓库固定为 `D:\Lie-DownCraft\cartography-1.21.1`，平台固定为 `NeoForge 1.21.1`。
- MVP 只实现 PDF 的 Stage 1：`top-down raster`、懒生成瓦片、`dirty chunk -> metatile -> ancestor invalidation`、维度切换、玩家 marker、TPS budget、版本化 tile namespace、可浏览 Web 地图。
- 本轮除设计稿与实施计划外，还必须把同版 MVP 计划复制一份到项目根目录。
- Git 规则固定为：先创建 GitHub 远程仓库并配置 `origin`，之后每个实现步骤都形成独立 commit，且该 commit 必须同时包含对应文档更新，并立即 push 到远程分支。

## Key Changes
### 1. 仓库、远程与提交工作流
- 第 0 步先创建新的 GitHub 仓库，本地执行 `git init -b main`，配置 `origin`，验证首次 push 可用。
- `main` 只承载基线与稳定合并结果；实际实现在 `feat/neoforge-mvp-bootstrap` 分支推进，并在远程持续可见。
- 首个远程提交只做仓库基线：
  - 补齐 `.gitignore`：`.worktrees/`、Gradle/build 产物、运行目录、前端构建产物、`node_modules`
  - 建立分支规范：`docs/*`、`chore/*`、`feat/*`、`fix/*`
  - 建立提交规范：Conventional Commits
- 每次 commit 的硬约束：
  - commit 必须对应一个单一实现步骤或一个单一文档步骤
  - 凡是改动代码、配置、构建、接口、测试的 commit，必须同时更新相关文档
  - commit 后立即 `git push origin <current-branch>`
  - 不允许积攒多个实现步骤后再一起提交
- 为保证“每次 commit 都有文档工作”，新增一个持续更新文档：
  - `docs/implementation-log.md`
  - 每个 commit 都追加：目的、变更点、验证结果、下一步
- worktree 规则固定：
  - 只有在仓库完成首次远程提交后，后续并行开发才允许启用 worktree
  - worktree 目录必须被 `.gitignore` 忽略

### 2. 文档交付与根目录计划副本
- 正式文档落点固定为：
  - 设计稿：`docs/superpowers/specs/2026-06-27-cartography-neoforge-mvp-design.md`
  - 实施计划：`docs/superpowers/plans/2026-06-27-cartography-neoforge-mvp.md`
  - 协作/提交流程：`docs/git-workflow.md`
  - 持续实现日志：`docs/implementation-log.md`
- 项目根目录额外保留一份同步的 MVP 计划副本：
  - `CARTOGRAPHY_MVP_PLAN.md`
  - 内容必须与 `docs/superpowers/plans/2026-06-27-cartography-neoforge-mvp.md` 保持一致
- 文档提交顺序固定：
  1. 远程仓库与仓库基线
  2. 设计稿
  3. 实施计划
  4. 根目录计划副本
  5. Git 工作流说明
  6. 实现日志框架
  7. 再进入代码与前端实现
- 后续每次代码提交都至少同步更新：
  - `docs/implementation-log.md`
  - 若接口、配置、行为、范围发生变化，同时更新设计稿或实施计划对应章节
- 设计稿必须明确：MVP 范围、NeoForge 专属架构、黑色 pending tile 契约、OpenLayers 前端边界、marker 默认关闭策略。
- 实施计划必须按可执行任务序列编写，并在每个任务尾部包含 commit/push 要求。

### 3. Gradle 与依赖源策略
- Gradle 公共源显式改为阿里云镜像优先，不再只写默认模板仓库。
- 插件解析与项目依赖分开处理：
  - `pluginManagement` 先走阿里云 Gradle 插件镜像与公共镜像
  - 常规依赖解析优先走阿里云 `mavenCentral` / `public` 镜像
- NeoForged、Parchment、Minecraft 模组生态专用仓库保留官方源，不强制替换成阿里云，避免镜像缺包导致构建不可用。
- 计划里要把 Gradle 改源拆成单独的早期步骤，并要求：
  - 改源 commit 同时更新 `docs/git-workflow.md` 或 `docs/implementation-log.md`
  - 改源后立即做一次非破坏性依赖解析验证
  - 如果阿里云缺失某个专用依赖，按“阿里云优先 + 官方补位”的固定策略处理，不临场重做决策

### 4. 后端 MVP 架构
- 清理 NeoForge 模板示例代码，拆成独立子系统：模组启动与配置、世界快照提取、渲染管线、tile store、任务调度、HTTP 服务。
- 采用“主线程采样/快照，后台渲染/切片，文件系统落盘”的结构：
  - 世界事件捕获 block/chunk 变化
  - 保守映射到 dirty chunk 集
  - dirty chunk 映射到 max-zoom tile
  - max-zoom tile 归并成 metatile job
  - 渲染完成后切出 256 tile，并异步维护 ancestor invalidation
- 首次覆盖采用懒生成：
  - 请求缺失瓦片时立即返回黑色 pending tile
  - 同时将 metatile job 入队
  - miss 响应必须带 `Cache-Control: no-store`
  - 响应头固定带 `X-Cartography-Tile-State: pending` 与 `Retry-After`
  - 服务端预生成一份固定黑色 tile 字节，所有 miss 复用，不做逐请求绘制
- 内嵌 HTTP 服务只承担静态前端、tile API、marker API、manifest、health，不承担 Alpha 级编辑或权限系统。
- 文件系统 tile 路径固定为：
  - `/tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp`
- 关键公共接口固定：
  - `GET /manifest.json`
    - 返回 `tileSize`、`minZoom`、`maxZoom`、`pixelsPerBlockAtMaxZoom`、`dimensions`、`defaultDimension`、`tilesetVersion`、`tileUrlTemplate`、`markerMode`、`pendingTileRetryMs`
  - `GET /tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp`
    - 命中返回真实瓦片，miss 返回黑色 pending tile 并异步排队
  - `GET /markers?dimension={dimension}`
    - 返回 `players: [{ uuid, name, dimension, x, z, updatedAt }]`
  - `GET /healthz`
    - 返回服务存活和渲染队列简要状态
- 配置面固定为四组：`web`、`renderer`、`scheduler`、`markers`。
- `tilesetVersion` 由 `renderer profile + renderer code version + material table version + configured pack signature` 组合生成。

### 5. 前端 MVP 架构
- 新增独立 `frontend/` 子项目，采用 `Vite + OpenLayers`。
- 前端只做 MVP 浏览能力：维度切换、缩放/平移、版本化 raster tile 加载、玩家 marker 轮询、pending tile 重试、基础状态提示。
- 前端启动先读 `manifest.json`，不硬编码 zoom、dimension、tile URL、retry 间隔。
- tile 加载固定使用自定义 `tileLoadFunction`：
  - 通过 `fetch + blob` 读取图片与响应头
  - 通过 `X-Cartography-Tile-State: pending` 判断是否为未就绪瓦片
  - 对可见 pending tile 按 `pendingTileRetryMs` 或 `Retry-After` 短周期重试
  - 不允许通过“像素是黑色”推断 miss 状态
- marker 更新采用定时 HTTP 轮询，不做 SSE / WebSocket。
- marker 默认关闭；只有 `markerMode != off` 时前端才启用 marker 层。
- 前端构建产物必须打包进模组静态资源目录，由内嵌 HTTP 服务直接对外提供。

## Test Plan
- Java 单元测试必须覆盖：
  - MC 坐标 -> view -> tile -> MC 坐标 round-trip
  - metatile grouping
  - ancestor invalidation
  - `tilesetVersion` 切换
  - marker 默认关闭行为
  - pending tile 响应头与 `no-store` 语义
- Java 集成测试必须覆盖：
  - 内嵌 HTTP 服务启动
  - `manifest.json` 契约
  - tile miss 返回黑色 pending tile 且成功入队
  - dirty 事件触发后 max-zoom 与 ancestor tile 刷新
  - TPS 低于阈值时调度器暂停抢占
  - Gradle 改源后基础依赖解析仍可成功
- 前端测试必须覆盖：
  - manifest 加载成功/失败
  - OpenLayers tile URL 组装
  - 基于响应头的 pending tile 重试
  - marker 轮询在 `off/on` 模式下的行为
- 手工验收必须覆盖：
  - 首次访问未生成区域时先见黑底 tile，随后自动变成真实瓦片
  - 连续平移/缩放不会压垮服务
  - overworld/nether/end 切换正常
  - 修改世界方块后对应区域在可接受时间内更新
  - marker 默认关闭时不泄露玩家位置；开启后轮询显示正常
  - GitHub 远程上可以看到每个步骤的独立 commit 与同步文档更新

## Assumptions And Defaults
- 目标仓库继续使用 `NeoForge 1.21.1`，不做多平台抽象。
- GitHub 远程仓库作为实现前置条件，在任何代码提交前创建完成。
- HTTP 服务内嵌在模组进程内；sidecar 延后。
- 前端采用独立子项目；地图引擎固定为 `OpenLayers`。
- 首屏采用懒生成；tile miss 返回黑色 pending tile 并异步排队。
- 玩家 marker 属于 MVP，但默认关闭、配置开启。
- Gradle 公共仓库走阿里云优先；NeoForged/Parchment 专用仓库保留官方补位。
- 根目录计划副本文件名固定为 `CARTOGRAPHY_MVP_PLAN.md`。
- 每个 commit 必须同时包含文档更新；默认至少更新 `docs/implementation-log.md`。
- 文档默认中文，接口字段名、提交信息、代码标识保持英文。
