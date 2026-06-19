# Contributing

Thanks for taking this branch seriously.

This repository is not trying to be the biggest TVBox fork. The goal is narrower and harder: make playback, HDR routing, subtitle handling, fullscreen controls, and TV interaction behave like a product instead of a patch pile.

## What we maintain

The current release line is split into three delivery tracks:

- `java32`: mainstream 32-bit Android TV / smart-screen devices
- `java64`: 64-bit Android phones / tablets / boxes
- `hisense32`: Hisense-focused 32-bit TV build

When you contribute, please state which track your change affects.

## Before opening an issue or PR

- Check the latest release first: `v0.1`
- Reproduce on a clean install if possible
- Confirm whether the issue is specific to:
  - source type
  - container format
  - codec
  - HDR mode
  - subtitle type
  - fullscreen / remote interaction

## Good bug reports

A useful report for this project usually includes:

- app version and build variant
- device brand / model
- Android version
- 32-bit or 64-bit environment
- whether the failing stream is MP4 / MKV / TS / WebM
- whether the stream is SDR / HDR10 / HDR10+ / Dolby Vision
- whether the issue happens on the native system path or the compatibility path
- steps to reproduce
- logs or screenshots when available

Do not post private subscription URLs, tokens, cookies, or personal media links in public issues.

## Good pull requests

Please keep PRs focused.

- One topic per PR
- Do not mix UI polish, playback routing, source behavior, and build-system cleanup into one large patch unless they are tightly coupled
- Explain the exact problem being solved
- List the devices or environments you tested on
- Call out anything that might affect `java32`, `java64`, or `hisense32` differently

## Playback-sensitive changes

If your change touches player routing, subtitle loading, seek behavior, HDR handling, or fullscreen interaction, test as many of these as you can before opening a PR:

- normal MP4 playback
- HDR10 / HDR10+ playback
- MKV playback
- Dolby Vision detection and fallback behavior
- enter fullscreen
- exit fullscreen
- seek / resume
- subtitle auto-selection
- source switching
- back key behavior with overlays or menus visible

## Build expectations

The repository is arranged so runtime dependencies and caches can stay inside the project directory.

Please avoid committing:

- APK artifacts
- keystore files
- local SDK or JDK paths
- personal logs
- source subscriptions or private test data

## Project style

- Favor clear, reversible changes over clever patches
- Probe streams from real metadata, not from filenames
- Prefer predictable TV behavior over mobile-style interaction
- Avoid shipping half-fixes that solve one route by breaking another

## Legal note

This project does not ship media catalogs, playlists, or subscription sources.

Only contribute code, assets, and test material that you have the right to use.
