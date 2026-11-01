# ChatApp Android

Native Kotlin and Jetpack Compose registration/login client for the ChatApp
authentication API, direct conversation search, persistent text messaging, and
authenticated real-time message updates. Refresh tokens are encrypted with an
AES-GCM key held in Android Keystore. Conversation lists, recent messages, and
user-search results are cached locally with AES-GCM encryption and displayed
before a network refresh; sending still requires a network connection. FCM
notifications for background incoming calls, location, groups, attachment
sharing, and E2EE have not been implemented.

One-to-one audio/video calls use WebRTC media with authenticated Socket.IO
signaling. The call screen supports accept/decline/end, mute, speaker routing,
camera on/off, and switching cameras. Calls require the app to be online and
open on both devices; configure TURN credentials on the backend for reliable
connections across restrictive NATs. Background ringing and push delivery
require an FCM integration and are not available.

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
