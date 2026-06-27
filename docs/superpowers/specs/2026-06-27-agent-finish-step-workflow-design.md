# Agent Finish-Step Workflow Design

## 背景

当前仓库需要一套面向 `Codex` 与 `Claude` 的硬阻断工作流，确保每次完成一个“小功能”后，代理不能直接提交代码，而必须按固定顺序执行：

1. 启动 subagent 做 code review
2. 更新与改动类型对应的正式文档
3. 新建一份独立功能文档，记录本次功能的目的、改动、验证与 review 结论
4. 提交到本地 git
5. push 到远程 GitHub 仓库

该约束只面向代理，不要求强制约束所有人类开发者。

## 当前现状

- 仓库根目录已有 `.agents/`、`.github/`、`src/` 等目录。
- 当前没有 `docs/`、`tools/`、`AGENTS.md`、`CLAUDE.md`。
- 当前存在异常 `.git/` 目录，但 `git status` 结果表明这里不是一个有效仓库。
- `gh` CLI 当前不可用，因此远程 GitHub 仓库不能依赖 `gh repo create`。
- `MVPPLAN.md` 已明确要求：
  - 先创建 GitHub 远程仓库并配置 `origin`
  - 每个实现步骤独立 commit
  - 每次 commit 必须包含文档更新
  - commit 后立即 push 到远程分支

## 目标

- 为 `Codex` 与 `Claude` 提供统一且明确的仓库级工作流约束。
- 将 `subagent review -> 文档更新 -> commit -> push` 固化为唯一允许的收口路径。
- 让“是否允许提交”变成可执行检查，而不是仅靠提示文字。
- 与 `MVPPLAN.md` 的提交要求保持一致。
- 在首次实现时补齐本地 git 仓库与 GitHub 远程仓库 bootstrap。

## 非目标

- 不为所有人类开发者增加本地 git hook 强制校验。
- 不在本轮引入 CI 作为提交前置条件。
- 不实现多种提交流程入口；只保留一个标准入口。

## 关键定义

### 小功能完成

满足以下条件即可认为一个小功能完成，进入收口流程：

- 当前改动服务于一个单一、清晰的目标
- 相关代码和测试或验证已完成到可 review 状态
- 当前不依赖后续未完成步骤才能理解或验证其行为

### 正式文档

“正式文档”指用于描述仓库当前行为、设计、计划或使用方式的文档，例如：

- `README.md`
- `MVPPLAN.md`
- `docs/agent-workflow.md`
- `docs/superpowers/specs/*.md`
- `docs/superpowers/plans/*.md`

### 独立功能文档

每次收口必须新增一份独立文档到 `docs/changes/`，文件名形如：

- `YYYY-MM-DD-<step-slug>.md`

文档必须包含：

- 本次功能目的
- 主要改动
- 验证方式与结果
- subagent review 结论
- 更新了哪些正式文档

## 方案选择

采用“方案 B：指令文件 + 单一收口脚本”。

原因：

- 仅靠 `AGENTS.md` / `CLAUDE.md` 提示不足以构成硬阻断
- 仅靠脚本而无代理指令文件，代理仍可能绕过统一入口
- 组合使用后，代理层负责禁止绕过，脚本层负责校验是否满足提交条件

## 目标结构

需要新增以下文件与目录：

- `AGENTS.md`
- `CLAUDE.md`
- `docs/agent-workflow.md`
- `docs/reviews/`
- `docs/changes/`
- `tools/finish-step.ps1`

### AGENTS.md 与 CLAUDE.md

两个文件内容保持一致或高度一致，明确以下规则：

- 禁止直接执行 `git commit`
- 禁止直接执行 `git push`
- 完成一个小功能后，必须调用 `tools/finish-step.ps1`
- 如果 `finish-step.ps1` 失败，代理必须先修复失败原因，再重新运行
- 不允许忽略 `Critical` 或 `Important` 级别 review 问题后继续提交

### docs/agent-workflow.md

该文件作为单一事实来源，定义：

- 收口流程顺序
- “正式文档”的判定规则
- review 结果的通过标准
- 小功能文档模板
- 提交信息建议格式

`AGENTS.md` 与 `CLAUDE.md` 只做高优先级约束与入口说明，细则统一引用这里。

### tools/finish-step.ps1

这是唯一允许的收口入口。脚本负责：

1. 检查当前目录是否为有效 git 仓库
2. 检查 `origin` 是否存在
3. 检查当前分支是否已配置上游或可推送目标
4. 检查 review 文档是否存在且属于本次步骤
5. 解析 review 文档，确认：
   - `Ready to merge? Yes`
   - 不存在未解决的 `Critical`
   - 不存在未解决的 `Important`
6. 检查至少一个正式文档发生了改动
7. 检查新增了一份本次步骤的 `docs/changes/*.md`
8. 检查独立功能文档中已填写验证结果
9. 执行 `git add`
10. 执行 `git commit`
11. 执行 `git push`

脚本应在任何前置条件不满足时直接退出并返回非零状态。

## Review 工作流

每次小功能完成后，主代理必须先启动一个 reviewer subagent。

reviewer 输入至少包含：

- 本次功能描述
- 对应设计稿或计划要求
- 本次改动的 git 范围或待提交改动摘要

reviewer 输出需保存到 `docs/reviews/YYYY-MM-DD-<step-slug>.md`，并沿用以下结构：

- `Strengths`
- `Issues`
- `Critical`
- `Important`
- `Minor`
- `Recommendations`
- `Assessment`
- `Ready to merge?`

阻断规则：

- `Ready to merge? With fixes` 视为失败
- `Ready to merge? No` 视为失败
- 任一 `Critical` 视为失败
- 任一未解决 `Important` 视为失败

若第一次 review 未通过，必须修复并再次发起 review，直到通过。

## 文档更新规则

每次收口都必须有两类文档变更：

### 1. 正式文档更新

必须至少更新一份正式文档，更新规则按改动类型决定：

- 用户可见行为变化：更新 `README.md` 或使用说明文档
- 架构或边界变化：更新设计稿
- 实施顺序或范围变化：更新计划文档
- 新增工作流规范：更新 `docs/agent-workflow.md`

### 2. 独立功能文档

必须新建 `docs/changes/YYYY-MM-DD-<step-slug>.md`，固定包含：

- `## Purpose`
- `## Changes`
- `## Verification`
- `## Review`
- `## Formal Docs Updated`

脚本会把缺失章节视为失败。

## Git 与远程仓库 Bootstrap

### 本地仓库修复

当前 `.git/` 目录异常，说明仓库处于不完整状态。

bootstrap 顺序：

1. 检查 `.git/` 是否是有效仓库
2. 若无效，则将当前异常 `.git/` 目录安全迁移到备份位置，例如 `.git.broken-20260627`
3. 重新执行 `git init -b main`
4. 重新建立仓库基线

### 远程 GitHub 仓库创建

远程仓库需遵守 `MVPPLAN.md`：

- 先创建 GitHub 仓库
- 再配置 `origin`
- 再验证首次 push

由于当前 `gh` CLI 不可用，远程创建需要使用以下优先级：

1. 优先使用可用的 GitHub 连接器能力直接创建仓库
2. 若当前环境没有仓库创建 API，则使用可用的浏览器自动化能力在 GitHub Web 完成创建
3. 若两者都不可用，则实现本地工作流资产并明确记录“远程创建因环境能力缺失而阻塞”

### 首次提交顺序

按 `MVPPLAN.md`，首次提交顺序应为：

1. 修复本地 git 仓库并创建 GitHub 远程仓库
2. 仓库基线提交
3. 本设计稿
4. 后续实现计划
5. 工作流实现文件

## 错误处理

`finish-step.ps1` 需要给出明确失败原因，例如：

- `Not a valid git repository`
- `Remote origin is not configured`
- `Review file missing`
- `Review verdict is not Ready to merge: Yes`
- `No formal documentation file was updated`
- `Feature change note missing`
- `Verification section is empty`

失败信息应能直接指导代理修复问题，而不是只返回模糊错误码。

## 测试与验证

至少需要验证以下场景：

### 正向路径

- review 通过、正式文档已更新、独立功能文档已新增时，脚本可以成功 `commit` 和 `push`

### 阻断路径

- 缺少 review 文档时阻断
- review verdict 为 `With fixes` 时阻断
- 缺少正式文档更新时阻断
- 缺少独立功能文档时阻断
- `origin` 未配置时阻断
- 当前目录不是有效仓库时阻断

## 风险与取舍

- 该方案对代理形成强约束，但对完全不遵守仓库指令的人类开发者没有技术强制力。
- review 结果解析依赖文档格式，因此 review 模板必须固定。
- 如果 GitHub 远程创建能力在当前环境缺失，bootstrap 可能需要借助浏览器自动化或等待用户提供远程仓库地址。

## 实施结果预期

完成后，仓库应具备以下行为：

- `Codex` 和 `Claude` 在该仓库内默认不能直接提交
- 代理必须通过统一脚本完成每个小功能的收口
- 每次提交都有对应 review 记录、正式文档更新和独立功能文档
- 本地仓库与 GitHub 远程仓库状态恢复到可持续开发状态
