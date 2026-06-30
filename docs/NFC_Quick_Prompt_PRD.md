# NFC Quick Prompt — PRD

> Product Requirements · Experimental · Jun 2026

## 目标

用户在 Settings → Experimental → NFC Quick Prompt 里输入一段多行 prompt，写入 NTAG215 NFC tag。之后亮屏（不解锁或已解锁均可，ROM 适配差异）靠近 tag，系统自动拉起 App，新建 session，填入 prompt，按写入时的设置决定直接发送或等待确认。

## 用户场景

用户把一个常用的 prompt（如"帮我审查最新的 diff 并给出改进建议"）写入 NFC tag，贴在桌面上。每次想触发这个任务时，手机亮屏状态下靠近 tag，App 自动拉起并开始执行。免去解锁、找 App、新建 session、打字的全部步骤。

## 功能需求

### Settings UI

- 启用开关（默认关）：关闭时 App 不响应 NFC tag 触发
- 多行 prompt 输入框，实时显示 UTF-8 字节用量 / 上限
- Auto-send 开关：开启=直接发送，关闭=填入输入框等用户确认
- Write to tag 按钮：启动写入流程

### 写入流程

- 透明 Activity 提示用户贴 tag
- 生成 URI：`opencode://prompt?a={0|1}&p={urlencoded prompt}`
- 字节校验：URI 总字节数 ≤ 504（NTAG215 用户可用），超过则拒绝并提示
- 写入成功/失败 toast

### 触发流程

- 系统扫到匹配 tag → 拉起 MainActivity → onNewIntent
- 解析 URI，提取 prompt、autoSend
- 校验 settingsManager.nfcEnabled，未启用则忽略
- createSession → selectSession → setInputText(prompt) → 若 autoSend 则 sendMessage()

## 约束

- NTAG215 用户可用 504 字节，扣 NDEF overhead 后 prompt 上限 480 UTF-8 字节
- 熄屏不工作（Android tag dispatch 要求屏幕亮）
- tag 内容明文，无加密
- 自定义 scheme `opencode://` 仅 Android 可用，iOS 需另外走 Universal Links

## 非目标

- 熄屏触发（需 foreground service + reader mode，后续可选）
- 多 tag 身份管理
- iOS 适配
- tag 内容加密
- 从服务器拉 prompt 的间接模式