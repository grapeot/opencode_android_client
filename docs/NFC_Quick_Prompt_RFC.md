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

`MainActivity.onNewIntent(intent)`：
1. 检查 `intent.action == NDEF_DISCOVERED`
2. 取 `intent.data`，验证 scheme == "opencode" && host == "prompt"
3. 解析 query params `a` 和 `p`
4. 调 `viewModel.handleNfcPrompt(prompt, autoSend)`

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

- `onCreate`：取 SettingsManager 的 nfcPrompt/nfcAutoSend，生成 URI
- 校验字节 ≤ 504，超过则 toast 错误并 finish
- `NfcAdapter.getDefaultAdapter`，`enableWriteMode`（foreground dispatch）
- `onNewIntent` 收到 tag → `Ndef.get(tag).writeNdefMessage(msg)` → toast 成功/失败 → finish

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

- **误触发**：桌面上的 tag 反复触发。用 nfcEnabled 开关缓解。
- **安全**：prompt 明文写在 tag 上，任何人拿到 tag 可读。不要写入敏感内容。
- **ROM 兼容**：锁屏亮屏下 NDEF_DISCOVERED 行为不一致，本期只承诺已解锁。
- **字节不足**：480 字节对短 prompt 够用，长 system prompt 放不下。后续可走"tag 存 ID + 服务器拉内容"间接模式。