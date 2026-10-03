# Duplex for Android

> **Work in progress** · P0 shell. Desktop version: [duplex](https://github.com/Eric-huang799/duplex)

一部人和 AI 共用的 Android 浏览器：人看渲染页面，AI 通过内置模型 API 读取页面结构并操控浏览器，人可随时接管。

A shared browser for a human and an AI on Android — the human sees the rendered page, the AI reads the DOM/source and drives the browser through built-in model APIs, with instant human takeover.

## Status

- [x] **P0** shell — WebView + address bar (search / URL) + back / forward / reload
- [x] **P1** multi-tab (create / switch / close), start page (clock + search), dark / light theme, non-web scheme handling (`baiduboxapp://` etc.), `_blank` → new tab
- [x] **P2** built-in agent — OpenAI-compatible streaming (chat/completions + SSE + tool calls), 18 browser tools (snapshot/click/type/scroll/wait/...), chat panel (tablet side panel / phone fullscreen), tool cards, stop button, auto-scroll
- [x] **P3** on-page visualization (cursor ring / element highlight / status pill), background typing (no soft keyboard), persistent chat history (restored after restart)
- [ ] **P4** tablet polish, signing, GitHub release (screenshot tool deferred)

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK with `compileSdk 34` (set `sdk.dir` in `local.properties`).

## License

[MIT](LICENSE)
