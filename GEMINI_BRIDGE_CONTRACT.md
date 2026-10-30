# Si Gyi Gemini handoff contract

The frontend accepts files from the Android shell through `window.SiGyiRecapBridge.importFile(payload)` or the `sigyi:downloaded-file` event.

Supported payload properties:
- `name` / `fileName`: downloaded filename
- `type` / `mimeType`: MIME type
- `base64`: file bytes encoded as base64 (data URL prefix is accepted)
- `file` or `blob`: Blob/File when called from in-page JavaScript

Recognized file types:
- `.mp4`, `.mov`, `.m4v`, `.webm` or `video/*` → source video
- `.wav` or `audio/*` → narration audio
- `.srt` or SubRip MIME → timed subtitles

When both video and subtitle/audio assets exist, import automatically opens Video Style. WAV is rendered as the replacement narration track; SRT timestamps are used as the subtitle timeline. Without WAV, source-video audio is preserved.
