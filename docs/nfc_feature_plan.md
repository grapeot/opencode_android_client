# NFC Quick Prompt — Feature Plan (Android)

## Goal

用户在 Settings → Experimental 里输入一段多行 prompt，写入 NTAG215 NFC tag。之后**不解锁/不打开 App**（亮屏即可）靠近 tag，系统自动拉起 App，新建 session，填入 prompt，按写入时的设置决定直接发送或等待确认。

## Hard constraints

- **NTAG215**：用户可用 504 字节，NDEF 封装开销 ~24 字节，**prompt 上限 480 UTF-8 字节**（≈160 中文字 / 480 英文字）。App 内按 `String.toByteArray(UTF_8).size` 限制，写入前再校验一次。
- **熄屏不工作**：Android tag dispatch 要求屏幕亮。熄屏触发需要 foreground service + `enableReaderMode`，本期不做，留作后续可选项。
- **亮屏锁屏**：`NDEF_DISCOVERED` 在部分 ROM 能拉起 exported Activity，但不保证；本期只承诺"亮屏已解锁"。
- tag 必须有 NDEF 内容且匹配 manifest 声明的 scheme，空 tag 不触发。

## Architecture

### 1. Manifest 改动（`AndroidManifest.xml`）

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="false" />
```

给 `MainActivity` 加 `launchMode="singleTop"`（NFC 触发时走 `onNewIntent` 而非重建实例）并加 intent-filter：

```xml
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="opencode" android:host="prompt" />
</intent-filter>
```

匹配 tag 里写的 URI `opencode://prompt?autoSend=1&p=<urlencoded prompt>`。

### 2. NDEF tag 内容格式

URI scheme：`opencode://prompt`

Query params：
- `p` — prompt 文本，URL-encoded UTF-8
- `a` — autoSend：`0` = 等待确认（填入输入框不发送），`1` = 直接发送
- `s` — 可选 agent name（留空用当前默认）
- `m` — 可选 model id（留空用当前默认）

写入时 App 负责生成完整 URI 并写入 NDEF UriRecord。480 字节上限要扣除 URI 前缀 `opencode://prompt?a=0&p=` 约 25 字节，实际 prompt payload 约 **455 字节**。App 输入框实时显示剩余字节数。

### 3. Settings UI（`SettingsSections.kt` 新增 `NfcSection`）

放在 AppearanceSection 之后，标注 "Experimental"：

- **启用开关**：`NFC quick prompt`（默认关）
- **Prompt 多行输入框**：`OutlinedTextField` multiline，实时显示 `bytes used / 480`
- **Auto-send 开关**：直接发送 / 等待确认
- **Agent 下拉**（可选，留空=当前默认）
- **Model 下拉**（可选，留空=当前默认）
- **Write to tag 按钮**：点击后启动 `NfcWriterActivity`（一个透明 Activity，调 `NfcAdapter` foreground write）提示用户贴 tag，写入成功后 finish

### 4. Settings 持久化（`SettingsManager`）

新增属性（EncryptedSharedPreferences，跟随现有 `themeMode` 模式）：

```kotlin
var nfcEnabled: Boolean
var nfcPrompt: String
var nfcAutoSend: Boolean
var nfcAgent: String?   // null = default
var nfcModel: String?    // null = default
```

### 5. Intent 接收（`MainActivity`）

`onCreate` 已有读 intent 的先例（debug 注入）。新增：

- `onNewIntent(intent)`：解析 `NDEF_DISCOVERED`，取 URI，decode `p`/`a`/`s`/`m`。
- 转发给 `MainViewModel.handleNfcPrompt(prompt, autoSend, agent, model)`。
- 校验 `settingsManager.nfcEnabled`——未启用则忽略（防误触发）。

### 6. ViewModel 编排（`MainViewModel` 新增 `handleNfcPrompt`）

```
handleNfcPrompt(prompt, autoSend, agent?, model?):
  1. 若 settingsManager.nfcEnabled == false → return（静默忽略）
  2. 可选 override agent/model 到 state（若 tag 带了 s/m）
  3. createSession(title = null)  // 走现有 launchCreateSession
  4. selectSession 完成后 setInputText(prompt)
  5. 若 autoSend == true → sendMessage()
     否则只填入输入框，用户手动点发送
```

关键：`createSession` → `selectSession` 是异步链（现有 `launchCreateSession` 的 callback 是 `::selectSession`）。需要在 selectSession 完成后再 set inputText + send。可加一个 flag `pendingNfcSend`，在 `loadMessagesWithRetry` 或 state 更新后检查。

### 7. 写入流程（`NfcWriterActivity`）

新建透明 Activity（Compose 无 UI 或极简提示文本）：

- `onCreate` 调 `NfcAdapter.getDefaultAdapter`，`enableWriteMode`（foreground dispatch write）
- 用 `Ndef.get(tag).writeNdefMessage(NdefMessage(UriRecord(uri)))`
- 成功 toast / 失败 toast，然后 finish
- 生成 URI 时做字节校验：`uri.toByteArray(UTF_8).size > 504 → 报错拒绝写入`

## 文件改动清单

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | 加 NFC permission、feature、`launchMode="singleTop"`、NDEF intent-filter、注册 `NfcWriterActivity` |
| `util/SettingsManager.kt` | 加 5 个 NFC 相关属性 |
| `ui/settings/SettingsSections.kt` | 新增 `NfcSection` Compose section |
| `ui/settings/SettingsScreen.kt` | 在 section 列表里挂载 `NfcSection` |
| `ui/MainViewModel.kt` | 加 `handleNfcPrompt()`、`pendingNfcSend` flag |
| `ui/MainViewModelSessionActions.kt` | 可能需要扩展 `launchCreateSession` 支持"创建后立即发送" |
| `MainActivity.kt` | override `onNewIntent`，解析 NDEF URI 转发 |
| 新增 `NfcWriterActivity.kt` | 透明写入 Activity |
| `res/values/strings.xml` + `values-zh/strings.xml` | NFC 相关文案 |

## 风险与边界

1. **480 字节够不够**：一个简短 prompt 完全够；如果要塞长系统提示词，放不下。后续可选方案：tag 只存一个 ID，App 从服务器拉 prompt 内容（需联网）。本期不做。
2. **误触发**：用户把手机放桌上靠近 tag 会反复拉起。加 `nfcEnabled` 开关 + 可考虑"每个 tag N 分钟内只触发一次"去抖（本期用开关即可）。
3. **安全**：任何人拿到你的 tag 就能用你的 prompt 拉起你的 App 发送。prompt 本身明文写在 tag 上。不要把敏感 token 写进去。
4. **iOS 移植**：iOS 不能自定义 scheme，只能 https URL + AASA。同一套 tag 在 iPhone 上会弹通知但不会拉起本 App（除非也配 AASA 指向本 App）。跨平台共享 tag 需要额外设计，本期 Android only。
5. **多 tag 管理**：本期不区分 tag 身份，任何匹配 `opencode://prompt` 的 tag 都触发。如需多 tag 不同 prompt，每个 tag 写不同 URI 即可，App 不需要维护 tag 列表。

## MVP 范围

一期只做：
- Settings UI（启用开关 + prompt 输入 + autoSend 开关 + 写入按钮）
- 写入流程
- intent-filter 接收 + createSession + 填入 + 可选发送
- 字节限制校验

不做：
- 熄屏触发（foreground service + reader mode）
- 多 tag 身份管理
- iOS 适配
- tag 内容加密
- 从服务器拉 prompt 的间接模式

## 验证

1. 写入后用 `adb shell am start -a android.nfc.action.NDEF_DISCOVERED -d "opencode://prompt?a=1&p=hello"` 模拟 tag 触发，验证 App 拉起 + session 创建 + 发送。
2. 真实 tag：写入后亮屏靠近，观察是否自动拉起。
3. 字节边界：输入 480 字节 prompt，写入成功；481 字节，写入按钮 disabled。