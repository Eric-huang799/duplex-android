# Duplex for Android

> **Work in progress** · Android 8.0+ · signed APK on [Releases](https://github.com/Eric-huang799/duplex-android/releases). Desktop version: [duplex](https://github.com/Eric-huang799/duplex)

一部人和 AI 共用的 Android 浏览器：人看渲染页面，AI 通过内置模型 API 读取页面结构并操控浏览器，人可随时接管。

A shared browser for a human and an AI on Android — the human sees the rendered page, the AI reads the DOM/source and drives the browser through built-in model APIs, with instant human takeover.

> **平板效果更好。** 大屏下 AI 侧栏、页面标注与多标签阅读的体验明显更佳；手机同样可用（AI 面板自动转为全屏）。
>
> **Best on a tablet.** A large screen makes the AI side panel, page annotation and multi-tab reading notably better. Phones work too — the panel becomes a fullscreen sheet.

## Status

- [x] **P0** shell — WebView + address bar (search / URL) + back / forward / reload
- [x] **P1** multi-tab (create / switch / close), start page (clock + search), dark / light theme, non-web scheme handling (`baiduboxapp://` etc.), `_blank` → new tab
- [x] **P2** built-in agent — OpenAI-compatible streaming (chat/completions + SSE + tool calls), 18 browser tools (snapshot/click/type/scroll/wait/...), chat panel (tablet side panel / phone fullscreen), tool cards, stop button, auto-scroll
- [x] **P3** on-page visualization (cursor ring / element highlight / status pill), background typing (no soft keyboard), persistent chat history (restored after restart)
- [x] **P4** release signing (keystore) + GitHub release
- [x] **P5** in-app settings — multi-provider API management (per-vendor configs stored separately, one-tap switching, quick model switcher above the chat input, theme selector)
- [x] **P6** page annotation (box / circle / arrow / element pick, markers follow scrolling, structured hand-off to the AI, `annotation_mode` tool) + Markdown overhaul (GFM tables, task lists, clickable links) + coexisting `preview` build (`com.duplex.mobile.next`, installed side-by-side with a release build)
- [ ] narrow-phone layout polish & screenshot tool (deferred)

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK with `compileSdk 34` (set `sdk.dir` in `local.properties`).

Release builds (`./gradlew assembleRelease`) are signed when a local `keystore.properties` is present; otherwise they build unsigned.

## License

[MIT](LICENSE)
