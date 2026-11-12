# TVBox v0.2.9.4.1

This release adds external subtitle browsing and WebDAV/AList playback, bounds large-file playback buffering, and fixes port 9978 configuration application.

- The 9978 form now maps subscription and live addresses independently. Saved addresses appear in Settings and reload before the Home screen or live player uses them.
- WebDAV/AList credentials can be saved in Settings. Browse remote folders, play videos directly, load same-name sidecar subtitles automatically, or choose another subtitle from the current folder.
- Android's system file picker can browse local and mounted storage for external subtitle files without requiring a matching video filename.
- Large video files remain Range streamed with bounded buffering; memory is not allocated according to the total video size.
- All variants use version `0.2.9.4.1` (`versionCode 20941`) and the same signing certificate as v0.2.9.4.

Variants: `java32` (`armeabi-v7a`), `java64` (`arm64-v8a`), and `hisense32` (`armeabi-v7a`).
