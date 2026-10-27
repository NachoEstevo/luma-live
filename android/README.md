# Android demo

Native Kotlin app for Ray-Ban Meta glasses using Meta Wearables DAT 1.0.1. Current app version: 0.4.1. Camera capture and speech output were tested on Ray-Ban Meta Gen 2 with Android 17; other supported Android versions need hardware validation.

## Build

Requires JDK 21, Android SDK 36 and Build Tools 36. The Gradle wrapper is included.

1. Configure your local Android SDK through `ANDROID_HOME` or an ignored `local.properties` file.
2. Run from this directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb -d install -r app\build\outputs\apk\debug\app-debug.apk
```

Without account-specific configuration, the build uses the official DAT Developer Mode values. Pair your glasses in Meta AI and enable its Developer Mode using the current [official setup guide](https://wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit/). The SDK, permissions and feature availability depend on Meta's account, market and firmware requirements.

For a Beta integration, configure your own project/package/signature in Wearables Developer Center and set `mwdat.applicationId` and `mwdat.clientToken` only in ignored `local.properties`. Do not commit these values or distribute an account-configured debug APK. Use `-PdatDeveloperMode=true` to force the Developer Mode build without changing local Beta configuration.

## Connect the local backend

In the repository root, set the backend environment as described in [the README](../README.md), then:

```powershell
$env:PORT = '4174'
npm start
```

With the **phone**, not the glasses case, connected by USB:

```sh
adb -d reverse tcp:4174 tcp:4174
adb -d shell am start -n com.lumalive.probe/.MainActivity
```

## Use

1. Connect/register Luma with Meta AI through the app's connection panel. Wear the glasses and grant the requested DAT camera/microphone and Android notification permissions.
2. Read the provider disclosure, enable voice conversations and tap **Empezar conversación**.
3. After the ascending cue, say **Contame qué ves** or **Me describís lo que tengo delante**. Capture briefly closes Speech; questions then refer to that image.
4. Ask about a detail, say **No escuché bien** to repeat, or **Esperame un momento** to pause. Silence also pauses after the configured response window.
5. A descending cue confirms closed listening. **Retomar esta obra** resumes the context. **Terminar y olvidar obra** clears it.

The voice phrase **Hey Meta, start Luma Museo** additionally requires your own configured Beta Voice Invocation and accepted tester access. Installing this source alone does not provision that integration. DAT Voice Invocations and Speech are experimental features.

Eight model attempts per visit and ten-minute elapsed context validity apply. Resume preserves the quota and deadline. Android can defer expiry cleanup while asleep; expired context is rejected before reuse on activation/foreground. Pausing releases the visit wake lock. This is not continuous video analysis or a mobility aid.

Voice output uses available Android TTS voices; it is not Muse voice synthesis. Recognition quality, audio routing, interruptions, screen-locked behavior, TalkBack and battery life need separate physical checks. Manual Bluetooth recording/transcription diagnostics are separate from the main DAT Speech flow.
