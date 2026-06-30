# NFC Quick Prompt — RFC

> Technical Design · Jun 2026

## 1. Manifest 改动

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="false" />
```

MainActivity 新增 `android:launchMode="singleTop"`（NFC 触发走 onNewIntent 而非重建实例）和 NDEF intent-filter：

```xml
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="opencode" android:host="prompt" />
</intent-filter>
```

NfcWriterActivity 注册为单独 Activity（透明，noHistory）。

## 2. NDEF tag 格式

URI scheme：`opencode://prompt`

Query params：
- `a` — autoSend：`0` = 等待确认，`1` = 直接发送
- `p` — prompt 文本，URL-encoded UTF-8

写入使用 `NdefRecord.createUri(uri)` → `NdefMessage` → `Ndef.writeNdefMessage`。

## 3. 字节预算

NTAG215 用户可用 504 字节。NDEF TLV wrapper（`0x03` + length + payload + `0xFE`）≈ 3 字节。NDEF Record header（URI record：TNF=0x01 + type length + payload length + type "U" + prefix code）≈ 5 字节。实际可用 payload ≈ 496 字节。

保守取 **480 字节**作为 prompt UTF-8 上限。生成 URI 后再校验总字节数 ≤ 504。

## 4. Settings 持久化

`SettingsManager` 新增（EncryptedSharedPreferences）：

```kotlin
var nfcEnabled: Boolean       // KEY_NFC_ENABLED, default false
var nfcPrompt: String           // KEY_NFC_PROMPT, default ""
var nfcAutoSend: Boolean        // KEY_NFC_AUTO_SEND, default false
```

## 5. Settings UI

`SettingsSections.kt` 新增 `NfcExperimentalSection`，放在 SpeechRecognitionSection 之后、AboutSection 之前。

组成：
- SectionHeader："Experimental" + 副标题 "NFC Quick Prompt"
- Switch：启用/禁用
- OutlinedTextField multiline（minLines=3, maxLines=8）：prompt 输入
- 字节计数 Text：`bytes / 480`
- Switch：Auto-send
- Button：Write to tag（启动 NfcWriterActivity）

## 6. Intent 接收

`MainActivity` 在两个路径处理 NFC intent：

1. **`onCreate`**（冷启动）：app 被 tag 唤起时，intent 通过 `getIntent()` 到达。`onCreate` 末尾调用 `handleNfcIntent(intent)`。
2. **`onNewIntent`**（app 已在运行）：`singleTop` launchMode 下，tag dispatch 走 `onNewIntent`，调用 `handleNfcIntent(intent)`。

**关键**：`handleNfcIntent` 只在这两处调用，**不放在 Composable body 里**——之前误放在 `setContent` 的 lambda 中导致每次 UI 重组都重复触发，产生数百个垃圾 session。

**ViewModel 初始化竞态**：`onNewIntent` 可能在 `setContent` 给 `mainViewModel` 赋值之前到达。此时暂存 `pendingNfcPrompt: Pair<String, Boolean>?`，在 `setContent` 的第一行检查并消费。

**Debounce**：30 秒 cooldown。tag 贴着天线时系统每隔几秒重复 dispatch `NDEF_DISCOVERED`，debounce 防止创建多个 session。`lastNfcTriggerTimeMs` 在 `MainActivity` 实例上，不重置（不依赖 `onResume`）。

`handleNfcIntent` 逻辑：
1. 检查 `intent.action == NDEF_DISCOVERED`
2. 取 `intent.data`，验证 scheme == "opencode" && host == "prompt"
3. 检查 debounce（30s）
4. 解析 query params `a` 和 `p`
5. 若 ViewModel 已初始化 → 调 `viewModel.handleNfcPrompt(prompt, autoSend)`
6. 若未初始化 → 暂存到 `pendingNfcPrompt`，等 `setContent` 消费

## 7. ViewModel 编排

`MainViewModel.handleNfcPrompt(prompt, autoSend)`：
1. 若 `!settingsManager.nfcEnabled` → 静默 return
2. `createSession(title = null)` — 走现有 `launchCreateSession`
3. `selectSession` 完成后 → `setInputText(prompt)`
4. 若 autoSend → `sendMessage()`

实现方式：因为 `createSession` → `selectSession` 是异步链（callback 是 `::selectSession`），需要一种机制在 selectSession 完成后继续执行 set+send。方案：新增 `pendingNfcAction: NfcPendingAction?` 状态字段，在 `selectSession` 的消息加载完成回调里检查并执行。

```kotlin
data class NfcPendingAction(val prompt: String, val autoSend: Boolean)
```

在 `loadMessages` 完成后（或 `selectSessionState` 之后）检查 `pendingNfcAction`，若有则 setInputText + 条件 sendMessage，然后清空。

## 8. NfcWriterActivity

透明 Activity（`Theme.Transparent`，noHistory）：

- `onCreate`：取 SettingsManager 的 nfcPrompt/nfcAutoSend，生成 URI，校验字节 ≤ 504
- `onResume`：`enableForegroundDispatch`，使用正确构造的 PendingIntent（`Intent(this, NfcWriterActivity::class.java).addFlags(FLAG_ACTIVITY_SINGLE_TOP)`），使 tag 到达时走 `onNewIntent`
- `onNewIntent` 收到 tag → `Ndef.get(tag).writeNdefMessage(msg)` → toast 成功/失败 → finish
- `onPause`：`disableForegroundDispatch`
- 全程 `Log.d(TAG, ...)` 用于 logcat 调试（tag: `NfcWriterActivity`）

**设计教训**：
- `enableReaderMode` 在部分 ROM（MIUI/HyperOS）上无法抑制系统 "Empty Tag" 弹窗，改用 `enableForegroundDispatch`
- PendingIntent 必须用新构造的 Intent，不能传 Activity 自身的 `intent`（否则 `onNewIntent` 不触发）

## 9. 文件改动清单

| 文件 | 改动 |
|---|---|
| AndroidManifest.xml | NFC permission、feature、launchMode、NDEF intent-filter、NfcWriterActivity 注册 |
| util/SettingsManager.kt | 3 个 NFC 属性 + key 常量 |
| ui/settings/SettingsSections.kt | 新增 NfcExperimentalSection |
| ui/settings/SettingsScreen.kt | 挂载 NfcExperimentalSection |
| ui/MainViewModel.kt | handleNfcPrompt()、NfcPendingAction、AppState 字段 |
| MainActivity.kt | onNewIntent override |
| NfcWriterActivity.kt | 新增透明写入 Activity |
| res/values/strings.xml | NFC 文案 |
| res/values-zh/strings.xml | NFC 文案中文 |
| res/values/themes.xml | 透明 Activity 主题（如需） |

## 10. 风险

- **误触发**：桌面上的 tag 反复触发。用 nfcEnabled 开关 + 30s debounce 缓解。
- **安全**：prompt 明文写在 tag 上，任何人拿到 tag 可读。不要写入敏感内容。
- **ROM 兼容**：锁屏亮屏下 NDEF_DISCOVERED 行为不一致，本期只承诺已解锁。`enableReaderMode` 在 MIUI/HyperOS 上不抑制系统弹窗，改用 `enableForegroundDispatch`。
- **字节不足**：480 字节对短 prompt 够用，长 system prompt 放不下。后续可走"tag 存 ID + 服务器拉内容"间接模式。
- **Composable 重组**：`handleNfcIntent` 绝不能放在 `setContent` 的 Composable lambda 中——每次重组都会重复触发。只在 `onCreate` 和 `onNewIntent` 调用。