# Lunar Tear Android Starter

Minimal Android launcher for Lunar Tear server binaries.

## What this version does

- Starts `auth-server`, `octo-cdn`, and `lunar-tear` from `jniLibs/arm64-v8a`.
- Supports direct `assets.tar` mode, so the APK does not unpack hundreds of thousands of files.
- Extracts only `assets/release/20240404193219.bin.e` from `assets.tar`, because the game server needs master data as a regular file.
- Shows `READY` only after the CDN logs a tar asset store readiness line.
- Adds database backup buttons:
  - `Export db.zip`
  - `Import db.zip`

## Put binaries here

Copy fresh Android ARM64 Go binaries into:

```text
app/src/main/jniLibs/arm64-v8a/
```

Expected files:

```text
libauth-server.so
libocto-cdn.so
liblunar-tear.so
```

## Assets tar

Create the tar on Linux from the server folder:

```bash
cd server
tar -cf assets.tar assets
```

Then use `Import assets.tar (direct mode)` in the Android app.

## DB backup

`Export db.zip` writes the current `files/server/db` folder into a single zip file.

`Import db.zip` stops the server service and replaces `files/server/db` with the backup contents.


## Version 0.4 notes

- Dark theme enabled.
- Added Clear logs button.
- Added extra bottom padding so the last log line is not glued to the screen edge.
