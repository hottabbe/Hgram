# Hgram

Hgram is an open-source Android Telegram client based on [Nagram](https://github.com/NextAlone/Nagram), which in turn builds on Telegram for Android and NekoX. This repository keeps the Android application source, Hgram features, and the build inputs required to compile it.

## What is included

- Telegram for Android client and MTProto sources (`TMessagesProj/src/main/java/org/telegram`, `TMessagesProj/src/main/java/org/telegram/tgnet`).
- Nagram/NekoX client features (`tw.nekomimi.nekogram` and `xyz.nextalone.nagram`).
- Hgram voice processing, transcription, settings, tests, and UI resources.
- Android build scripts, Gradle wrapper, native build definitions, and source dependencies/submodules required by the app.

Generated build output, local SDK configuration, signing keys, and personal credentials do not belong in this repository. Third-party source dependencies are retained where the build needs them; Gradle/Maven artifacts are downloaded during build rather than committed as binaries.

## Requirements

- JDK 21
- Android SDK: platform 36 and Build Tools 36.0.0
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- Git with submodule support
- Network access for Gradle/Maven and Hgram's optional speech models

The Gradle wrapper downloads Gradle 8.13 automatically. Native toolchain components can be installed using Android Studio's SDK Manager or `sdkmanager`.

## Clone and build

```sh
git clone --recurse-submodules https://github.com/hottabbe/Hgram.git
cd Hgram
git submodule update --init --recursive
```

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your Android SDK directory. Create `local.properties` in the repository root with your local SDK path and your own Telegram API credentials from [my.telegram.org](https://my.telegram.org/auth):

```properties
sdk.dir=/path/to/Android/Sdk
TELEGRAM_APP_ID=your_app_id
TELEGRAM_APP_HASH=your_app_hash
```

Do not commit `local.properties`, API credentials, keystores, or service-account files. Then build a debug APK:

```sh
./gradlew :TMessagesProj:assembleDebug
```

For Java-only compile validation:

```sh
./gradlew :TMessagesProj:compileDebugJavaWithJavac
```

Debug APKs are written under `TMessagesProj/build/outputs/apk/`. Release builds require your own signing keystore and credentials; never reuse another publisher's signing material.

## Hgram speech features

Basic noise suppression uses the RNNoise implementation linked into the native client. Enhanced voice profiles use the sherpa-onnx Android library. GTCRN and DPDFNet2 speech-enhancement models, and optional Russian speech-recognition models, are downloaded on demand by the app and are not checked into the repository. Model downloads require network access and device storage.

## Dependencies

The build resolves AndroidX, Kotlin, Google/Firebase, sherpa-onnx, and other declared libraries from Gradle repositories. Native submodules and the media module are source dependencies and are necessary for the full client; removing them would make the project incomplete or unbuildable. Build caches and generated libraries are local outputs and should remain untracked.

## License and upstream

Hgram contains code derived from Telegram for Android and Nagram/NekoX. See [LICENSE](LICENSE), upstream license notices, and third-party notices in the source tree. Preserve applicable copyright and license notices when redistributing. Hgram is an independent third-party client and is not affiliated with Telegram.

- Nagram upstream: <https://github.com/NextAlone/Nagram>
- Telegram Android source: <https://github.com/DrKLO/Telegram>
