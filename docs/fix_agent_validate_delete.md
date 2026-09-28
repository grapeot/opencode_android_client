# Agent 校验与删除假成功

2026-09-27。两个客户端健壮性修复。shim 的 501 是有意设计，这里只改 Android 客户端。

## 背景

连上只暴露一个 session agent 的 host（例如 DSH shim 的 `GET /agent` 只返回 `build`）时，选中的 agent 名会从别的 server 残留下来。来源是设置里的 `selectedAgentName`，或从消息历史的 `info.agent` 推断。发送路径以前直接把这个名字放进请求体，host 回 501 `agent "grok" is not executable`。

删除是另一条漏检。`DELETE /session/:id` 在没有删除原语的 host 上回 501。`deleteSession` 以前丢掉 `Response`、不查 `isSuccessful`，501 被当成成功，本地列表把那一行删掉。下次刷新 session 又出现。同文件的 `sendMessage` 会查状态码，所以发送失败看得见、删除失败看不见。

## 改动

`effectiveSelectedAgent(selection, agents)`：列表为空时保留 selection（历史推断在 agents 还没加载时不能被清掉）；列表含该名字时保留；否则取第一个 visible agent，没有则 `"build"`。

三处应用：`loadAgents()` 成功后写回 state；`sendMessage()` 组请求前再校验一次；`launchLoadMessages` 把 per-session 值或历史推断写进 `selectedAgentName` 之前同样校验。Agent 选择器 UI 不恢复。

`OpenCodeRepository.deleteSession` 对齐 `sendMessage`：非 2xx 抛错，信息带状态码和 body。`launchDeleteSession` 本来就按 `Result` 分支，失败时显示错误、不删本地行，UI 层没改。

## 测试

`./gradlew testDebugUnitTest`：401 tests，0 failures，0 skipped。覆盖：纯函数（已知保留、未知回退到第一个 visible、空列表不动、没有 visible 时回 `"build"`）；`loadAgents` 反射用例（`"grok"` → `"build"`、`"build"` 保留、空列表不动）；消息加载把不在列表里的推断 agent 纠正掉；发送前把未知 agent 改成列表里的 visible agent；删除 501 为 `Result.failure` 且错误信息含状态码和 body；删除失败时本地行还在并显示错误。

既有 `loadMessages updates selected agent and preset model from last assistant` 仍断言 `"plan"`。该用例 agents 为空，空列表分支故意不动 selection。

未跑 `connectedDebugAndroidTest`，也没有装机。
