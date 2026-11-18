# ApkForge

Decompile, browse, rebuild and re-sign Android apps — on the device, no Termux needed.

* **`app/`** – the Android app (`com.gt.apkforge`, minSdk 26, targetSdk 34)
* **`engine/`** – the in-process engine: [jadx-core], [ARSCLib], smali/baksmali and [apksig],
  pure JVM so it runs both inside the app and on a desktop/CI JVM
* **`cli/apkforge`** – the original bash engine (Termux / Linux, uses apktool + jadx). Its
  project layout is the reference format both engines produce.

## Project layout (shared by app and CLI)

```
<out>/<slug>/
  input/         original + extracted/merged APKs   (app.apk | splits/ + merged.apk)
  apktool/       smali + decoded resources          -> rebuild
  jadx/          Java sources as a Gradle project (open in Android Studio)
  dist/          unsigned.apk, rebuilt-signed.apk
  rebuild.sh     shortcut for `apkforge rebuild <this dir>`
  info.txt       source/type/package/version + apktool:/jadx: status
  decompile.log  full tool output
  .done          marker (skipped on re-run unless -f)
```

`apktool/` from the CLI is real apktool output (`res/`, `smali_classesN/`, `apktool.yml`). The
app/engine writes the ARSCLib/APKEditor layout (`resources/package_N/res/`, `root/`,
`smali/classesN/`) – it can be rebuilt by the app, by `apkforge rebuild` (via APKEditor when
`apktool/resources/` exists, otherwise apktool) or by `java -jar APKEditor.jar b`.
`apkforge compare <A> <B>` normalises both layouts and reports identical/changed/only-in-one files
per category (`-y` lists them); its exit status is nonzero when manifests or smali differ.

## Building

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :engine:test :engine:installDist
engine/build/install/engine/bin/engine decompile some.apk -o /tmp/projects
engine/build/install/engine/bin/engine rebuild /tmp/projects/some
tools/engine-smoke.sh                 # what CI runs: decompile -> rebuild -> verify, .apks merge
```

Toolchain: AGP 9.1.0, Gradle 9.3.1 (wrapper), Kotlin 2.3, Java 17, compileSdk 36.

### Running desktop libraries on Android 8+

jadx/ARSCLib/apksig use a few Java 11 APIs that Android only added in API 28–35. D8 backports
most of them; `buildSrc/ApiCompatTransform.kt` rewrites the rest at build time to shims in
`engine/.../compat/Jdk11Compat.java`, and `tools/check-dex-api.py` fails CI if the final dex
references anything newer than minSdk 26.

## CI

Every push builds debug + release APKs, runs the engine tests, the dex API check, the
engine smoke test and the CLI compare check, and uploads `ApkForge-debug-<run>` as an artifact.
Pushing a `v*` tag
publishes a GitHub Release with `ApkForge.apk`.

The app and CI builds are signed with the committed `app/apkforge-debug.keystore` so new
builds install over old ones. Rebuilt APKs are signed with the engine's built-in
"ApkForge Debug" key (or your own key, see Settings). Neither is meant for publishing.

[jadx-core]: https://github.com/skylot/jadx
[ARSCLib]: https://github.com/REAndroid/ARSCLib
[apksig]: https://android.googlesource.com/platform/tools/apksig
