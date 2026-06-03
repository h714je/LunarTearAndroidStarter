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

## Building native server binaries

This Android launcher does not include prebuilt Go server binaries.

The `lunar-tear_patch/` directory contains helper files that must be used with the original `lunar-tear` server source tree:

```text
lunar-tear_patch/
  for_android.patch       Patch for the Go server
  build-android-libs.sh   Build script for Android arm64 binaries
```

### 1. Apply the patch to the original lunar-tear source

Clone or unpack the original `lunar-tear` source, then copy the patch into the source root:

```bash
cp /path/to/LunarTearAndroidStarter/lunar-tear_patch/for_android.patch .
patch -p1 < for_android.patch
```

The patch adds Android-friendly behavior:

```text
- embedded database migrations for lunar-tear
- direct assets.tar support for octo-cdn
- assets.tar index cache support
```

### 2. Generate protobuf files

From the `server/` directory of the original `lunar-tear` source:

```bash
cd server
make proto
```

### 3. Build Android arm64 binaries

Copy the build script into the `server/` directory:

```bash
cp /path/to/LunarTearAndroidStarter/lunar-tear_patch/build-android-libs.sh .
chmod +x build-android-libs.sh
```

Run it from the `server/` directory:

```bash
./build-android-libs.sh
```

This builds three Android arm64 binaries:

```text
libauth-server.so
libocto-cdn.so
liblunar-tear.so
```

The files are named `.so` intentionally so Android can package and extract them as native libraries.

### 4. Copy binaries into the Android launcher

Copy the generated files into this Android project:

```text
app/src/main/jniLibs/arm64-v8a/
```

Expected result:

```text
app/src/main/jniLibs/arm64-v8a/libauth-server.so
app/src/main/jniLibs/arm64-v8a/libocto-cdn.so
app/src/main/jniLibs/arm64-v8a/liblunar-tear.so
```

These `.so` files are generated build artifacts and should not be committed to Git.

### 5. Build the APK

Open this project in Android Studio and build the APK:

```text
Build -> Build Bundle(s) / APK(s) -> Build APK(s)
```

Or from the command line:

```bash
./gradlew assembleDebug
```

## Assets

Large game assets are not included in this repository.

The launcher expects an `assets.tar` file to be imported on the Android device. The tar archive should contain the original `assets/` tree:

```text
assets/release/...
assets/revisions/...
```

Recommended way to create it from the original server directory:

```bash
cd server
tar -cf assets.tar assets
```

Do not use `tar.gz`. The direct tar mode needs a plain `.tar` file so the CDN can seek inside it efficiently.

On first start, the CDN scans `assets.tar` and creates an index cache. Later starts reuse the cached index and should become faster.




## Version 0.4 notes

- Dark theme enabled.
- Added Clear logs button.
- Added extra bottom padding so the last log line is not glued to the screen edge.
