# ChatApp Android

Native Kotlin and Jetpack Compose registration/login client for the ChatApp
authentication API, direct conversation search, persistent text messaging, and
authenticated real-time message updates. Refresh tokens are encrypted with an
AES-GCM key held in Android Keystore. FCM notifications, calls, location,
groups, attachment sharing, offline message storage, and E2EE have not been
implemented.

## Requirements

- Android Studio and Android SDK Platform 36
- JDK 17
- The ChatApp backend running and reachable by the device/emulator

## Configure the API URL

For an Android emulator with the backend running on the development machine,
add this to the untracked `local.properties` file:

```properties
sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
chatapp.api.baseUrl=http://10.0.2.2:3000/
```

On a physical device, use the development machine's reachable LAN address and
allow that address in the backend development environment. The default release
URL is `https://seo-pabel.onrender.com/`. To override it locally, add
`chatapp.api.releaseBaseUrl=https://your-api-host/` to `local.properties`.
Release builds require HTTPS; no API credential belongs in this file or in
BuildConfig.

## Build

From this directory, run:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lint
.\gradlew.bat assembleRelease
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. The release APK is unsigned;
configure release signing securely in CI before distribution.

The backend and API documentation are maintained in the separate
[`chatAppBackend`](https://github.com/Pavelprfb/chatAppBackend) repository.
