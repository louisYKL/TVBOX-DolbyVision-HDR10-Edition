# Release Memory

This file records the release path already proven to work for this project so future release work does not waste time retrying broken approaches.

## Proven Path

- Release from `E:\apk\tvbox\_publish_repo` only. This is the umbrella repo that backs the public GitHub project.
- Build APKs in the three working trees first, then stage release assets locally under `_publish_repo\release\vX.Y.Z-github-assets\`.
- Required staged files:
  - `TITLE.txt`
  - `RELEASE_NOTES.md`
  - `SHA256SUMS.txt`
  - `TVBox_vX.Y.Z_java32.apk`
  - `TVBox_vX.Y.Z_java64.apk`
  - `TVBox_vX.Y.Z_hisense32.apk`
- Update the committed release docs in `_publish_repo`:
  - `CHANGELOG.md`
  - `README.md`
  - `RELEASE_NOTES_vX.Y.Z.md`
- Commit and push the `_publish_repo` source and docs first.
- Create and push tag `vX.Y.Z` from `_publish_repo`.
- Create or update the GitHub release through the GitHub REST API using the local GitHub credential from `git credential-manager`.
- Upload assets from the local staged directory.
- Only attach a `project-files.zip` snapshot if the user explicitly asks for a source archive asset. GitHub already provides source archives for the tag by default.

## Proven Evidence

- GitHub API verified on 2026-06-30:
  - `v0.1.8` is a published release with `RELEASE_NOTES.md`, `SHA256SUMS.txt`, `TITLE.txt`, and the three APKs.
  - `v0.1.7.1`, `v0.1.6`, `v0.1.5-r1`, and `v0.1.4` are also published releases.
  - `v0.1.9` exists as a draft release with uploaded assets, which proves the same API path still works.
- Local `_publish_repo` history matches this flow:
  - `b88e17b` = `release: v0.1.7`
  - `f4cdbfb` = `docs: prepare v0.1.8 release materials`
- Local staged asset directories already exist for successful releases:
  - `release/v0.1.7-github-assets/`
  - `release/v0.1.8-github-assets/`

## Known Time Waster

- Do not spend time on Chrome or Playwright release publishing in this environment unless the browser path is already confirmed working.
- On 2026-06-30 Playwright failed before page load with:
  - `spawn C:\Program Files\Google\Chrome\Application\chrome.exe EACCES`
- That is a local browser automation launch problem, not a GitHub auth problem and not a release-content problem.
- GitHub auth is already available locally through:
  - `git credential-manager`
  - stored credential target `git:https://github.com`
  - account `louisYKL`

## Standard Future Flow

1. Sync `_publish_repo` to the final source state for the target version.
2. Build all requested APKs.
3. Stage assets in `release/vX.Y.Z-github-assets/`.
4. Update `CHANGELOG.md`, `README.md`, and `RELEASE_NOTES_vX.Y.Z.md`.
5. Commit and push `_publish_repo`.
6. Tag and push `vX.Y.Z`.
7. Run `scripts/publish-github-release.ps1 -Version X.Y.Z` from `_publish_repo`.
8. Verify the release URL and uploaded assets.

## Notes

- The staged asset directory is local release workspace and should stay untracked.
- The public source of record is the Git tag in `_publish_repo`, not the temporary build trees.
- For this project, "manual release" means a controlled release from `_publish_repo` plus explicit uploaded assets, not browser clicking.
