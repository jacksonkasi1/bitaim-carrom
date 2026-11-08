# System Instructions: Android / Java Build Setup

Basic rules for this PC. Follow them whenever you build or install apps, whether you're a person or an AI agent.

---

## 1. Golden rule: keep C: free, put everything on D:

C: is small (about 20 GB free). D: has plenty of space (about 75 GB free).

- Install every dev tool, SDK, cache and project on **D:**.
- Never let build caches grow on C:.
- If a tool asks where to install, choose `D:\Tools\<name>`.

---

## 2. Where things live

| Item | Location |
|---|---|
| JDK 17 (Temurin) | `D:\Tools\jdk-17` |
| Android SDK | `D:\Tools\Android` |
| adb | `D:\Tools\Android\platform-tools` |
| sdkmanager | `D:\Tools\Android\cmdline-tools\latest\bin` |
| Gradle 8.9 (standalone) | `D:\Tools\gradle-8.9` |
| Gradle cache | `D:\Tools\gradle-home` |
| Projects | `D:\WORK\HACK\HACKTHON\<project>` |
| Test app (working template) | `D:\WORK\HACK\HACKTHON\hello-android` |

User environment variables (already set):

```
JAVA_HOME        = D:\Tools\jdk-17
ANDROID_HOME     = D:\Tools\Android
ANDROID_SDK_ROOT = D:\Tools\Android
GRADLE_USER_HOME = D:\Tools\gradle-home
```

- After you change any environment variable, close and reopen the terminal, VS Code or Android Studio.
- The old JDK 11 was removed. Use only JDK 17 or newer.
- The system-level (Machine) `ANDROID_HOME` still points to an old C: path. Your user variable takes priority, so ignore it. Fixing it needs admin rights.

---

## 3. Cleanup: do this whenever space is needed

These are all safe to delete. They come back automatically when needed.

| What | Where | How |
|---|---|---|
| Gradle cache (biggest, grows 1–3 GB+) | `D:\Tools\gradle-home\caches` | Delete the folder |
| Old Gradle wrapper versions | `D:\Tools\gradle-home\wrapper\dists` | Delete versions you no longer use |
| A project's build output | `<project>\app\build`, `<project>\build`, `<project>\.gradle` | Run `.\gradlew.bat clean`, or delete the folders |
| `node_modules` (React Native projects) | `<project>\node_modules` | Delete it; `npm install` brings it back |
| npm cache | `D:\Tools\npm-cache` | Run `npm cache clean --force` |
| Leftover installer zips | `D:\Tools\...\*.zip` | Delete them after extracting |
| Stray `.gradle` on C: | `C:\Users\jacks\.gradle` | Should not exist. Delete it if it appears. |

When to clean:
- C: drops below about **10 GB free**.
- D: drops below about **20 GB free**.
- A build behaves strangely. Run `clean` first, then delete the Gradle caches if that doesn't help.

Check free space:
```powershell
Get-PSDrive C,D
```

---

## 4. Tool rules

- **Java:** JDK 17 for all Android builds (Android Gradle Plugin 8.x needs 17 or newer).
- **Gradle:** always use the project's own `gradlew.bat`. Use `D:\Tools\gradle-8.9` only to create a wrapper when a project doesn't have one:
  ```powershell
  D:\Tools\gradle-8.9\bin\gradle.bat wrapper --gradle-version <version>
  ```
- **npm:** keep the cache on D:. Run this once:
  ```powershell
  npm config set cache D:\Tools\npm-cache
  ```
- **local.properties:** use forward slashes. Backslashes break the build.
  ```
  sdk.dir=D:/Tools/Android
  ```
- **SDK packages:** install them with sdkmanager so they land in `D:\Tools\Android`, for example:
  ```powershell
  sdkmanager "platforms;android-34" "build-tools;33.0.0"
  ```
- Download only from official sources: adoptium.net, services.gradle.org, Google/Android, nodejs.org.

---

## 5. Building apps for Android 13 / 14+

| Android | API level |
|---|---|
| 13 | 33 |
| 14 | 34 |
| 15 | 35 |

In `app/build.gradle`:
```gradle
compileSdk 35
minSdk 33     // 33 = Android 13+, 34 = Android 14+
targetSdk 35  // the Play Store needs a recent target
```

- You don't need older platform packages. Building against 35 works on Android 13 and 14 phones.
- Android Gradle Plugin 8.5+ with Gradle 8.7+ is needed for `compileSdk 35`.

Build and install:
```powershell
.\gradlew.bat assembleDebug     # build the APK
.\gradlew.bat installDebug      # install on the connected phone
.\gradlew.bat assembleRelease   # release APK (React Native: includes the JS bundle)
```

APK output: `app\build\outputs\apk\debug\app-debug.apk`

---

## 6. Oppo / ColorOS phone setup

1. Settings → About phone → tap **Version / Build number** 7 times to turn on Developer options.
2. In Developer options, turn on:
   - **USB debugging**
   - **Install via USB**
   - **Disable permission monitoring**
3. Plug in the phone, set the USB mode to **File transfer**, and tap **Allow** on the "Allow USB debugging?" popup.
4. Check the connection; the phone should show as `device`:
   ```powershell
   adb devices -l
   ```
5. Check the phone's Android version (33 = 13, 34 = 14, 35 = 15):
   ```powershell
   adb shell getprop ro.build.version.sdk
   ```
6. For APKs installed from a file: if "Display over other apps" is greyed out, go to App info → ⋮ → **Allow restricted settings**.

If the phone doesn't show up:
- Try another cable or USB port.
- Run `adb kill-server`, then `adb devices`.
- Install the Oppo or Google USB driver.

---

## 7. Quick health check

```powershell
java -version            # should be 17.x from D:\Tools\jdk-17
adb version              # from D:\Tools\Android\platform-tools
echo $env:GRADLE_USER_HOME
Test-Path $env:USERPROFILE\.gradle   # should be False
Get-PSDrive C,D
```
