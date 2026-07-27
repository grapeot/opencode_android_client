# Dynamic OpenCode Model Selector

## Summary

Replace the chat tab's hardcoded `ModelPresets.list` selector with the full model list returned by the configured OpenCode server's existing `GET /config/providers` API. Selection will persist by `providerId/modelId`, and sending a prompt will continue to pass the selected model through the existing `PromptRequest.model` payload.

## Key Changes

- Convert `ProvidersResponse` into `AppState.ModelOption` entries from all server providers/models:
  - `providerId = model.providerID/providerId if present, else provider.id`
  - `modelId = model.id if present, else map key`
  - display the model name plus provider identity for disambiguation
  - sort deterministically by provider then model
- Change `AppState.availableModels` to derive from `providers` instead of `ModelPresets.list`; before providers load or after load failure, the selector shows the existing "No models" empty state.
- Update selection resolution:
  - prefer per-session saved `providerId/modelId`
  - then infer from the latest assistant message
  - then global saved `providerId/modelId`
  - then server `default`
  - then legacy saved index mapped through `ModelPresets.list` as migration fallback
  - then first server model
- Extend `SettingsManager` with stable model persistence:
  - global selected model provider/id
  - per-session selected model provider/id
  - keep old index-based methods only for backward-compatible migration.
- On host/server profile change, clear stale providers until the new server's provider list loads.
- Keep `buildSelectedModel()` behavior: use selected dynamic model when valid, otherwise server default, otherwise omit model from prompt.

## Implementation Targets

- `app/src/main/java/com/yage/opencode_client/ui/MainViewModel.kt`: dynamic `availableModels`, selection defaults, host/profile reset behavior.
- `app/src/main/java/com/yage/opencode_client/ui/MainViewModelSessionActions.kt`: provider loading, message inference, send model resolution.
- `app/src/main/java/com/yage/opencode_client/util/SettingsManager.kt`: stable model persistence and legacy migration helpers.
- `app/src/main/java/com/yage/opencode_client/ui/chat/ChatTopBar.kt`: render dynamic menu labels without changing the top-level interaction.

## Test Plan

- Update AppState/model tests so `availableModels` is empty without providers and equals all server models when providers are present.
- Add tests for provider/model mapping, duplicate disambiguation, model id fallback, resolved provider id, and server default selection.
- Update ViewModel tests for:
  - `loadProviders()` populates dynamic options and selects server default
  - `selectModel()` persists provider/model globally and per session
  - `loadMessages()` restores per-session model or infers latest assistant model
  - `sendMessage()` sends the selected dynamic provider/model
  - legacy index migration still maps old saved presets when matching server models exist.
- Run:
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew testDebugUnitTest
  ```
- Do not run `connectedDebugAndroidTest` unless explicitly requested, and only target an emulator.

## Assumptions

- The desired behavior is the full OpenCode server model list, not a curated client list.
- `GET /config/providers` is the source of truth; no new server endpoint is needed.
- Existing prompt send API shape remains unchanged: `providerID` and `modelID`.
