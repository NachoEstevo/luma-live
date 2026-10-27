# Luma Museo

A museum companion for Ray-Ban Meta glasses and Android, powered by Meta Muse Spark. Choose an artwork, hear a description, ask follow-up questions and pause at your own pace.

This is a hackathon prototype. Glasses camera capture and audio output have been tested on real hardware. Automatic pause, context retention and natural intent handling have automated regression coverage; accessibility and field use still need evaluation with users.

## What works

- Capture an artwork from the glasses; close the camera while discussing that image.
- Speak through the glasses using Meta Wearables DAT Speech; hear Android text-to-speech output.
- Pause after 25 seconds of silence, with optional 45/60-second windows and audible cues.
- Resume the current artwork through a configured Meta voice invocation, without another capture or model request.
- Interpret common Spanish phrases for capturing, repeating, interrupting, pausing and ending. Ask for confirmation when a request is ambiguous.
- Keep artwork and conversation in memory. Ending a visit clears references. Context expires after ten minutes; Android sleep may defer cleanup until the next activation.

The Android demo currently uses a local backend over USB. It is not a standalone museum deployment. Artwork identification is tentative; there is no verified institutional catalog, live environment awareness or mobility guidance.

## Run the browser demo

Requires Node.js 22.13 or later. No external Node dependencies.

```sh
npm start
```

Open `http://127.0.0.1:4173`. Fixture mode works without an account, credentials or provider calls. Its sample artwork and responses are illustrative. The browser uses the computer/phone camera, not the glasses camera.

## Enable Meta API

Set credentials only in the backend process environment. No environment files or keys are distributed in this repository. For example, in PowerShell 7:

```powershell
$env:MODEL_API_KEY = Read-Host 'Meta Model API key' -MaskInput
$env:ENABLE_META = 'true'
$env:META_MODEL = 'muse-spark-1.3'
$env:MAX_META_CALLS = '24'
npm start
```

Provider calls require explicit consent in the UI. The process ceiling counts failed and cancelled attempts and resets on restart; it is not a persistent spending limit or a dollar budget. Provider data policies apply to submitted images, questions and audio. A subscription credential for Muse Code is not an API key for this application.

See [Android setup](android/README.md) to connect the glasses and use port 4174.

## Architecture

```text
Android: DAT camera -> selected image in memory -> local Node backend
         DAT Speech -> local intent or question -> Muse Spark -> Android TTS
         VisitService -> visible controls, bounded visit, pause/resume

Browser: camera or fixture -> local Node backend -> Muse Spark or fixture
```

The backend owns credentials, provider requests and limits. Android owns consent, image/history lifetime, SDK session handoff and speech controls. Manual camera/audio diagnostics remain available inside the Android app.

## Verify

```sh
npm run verify
```

Android: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` from `android/`. Tests do not validate glasses battery life, latency, speech recognition quality or accessibility in real museums.

## Privacy and security

The backend binds to loopback. Do not expose it publicly: it has no production user authentication or persistent quotas. The application does not write image/audio/conversation recordings to files; voluntary exports contain metrics. Meta SDK and provider policies apply independently. See [security notes](SECURITY.md).

Project code has no license selected yet. Third-party components retain their own terms; see [third-party notices](NOTICE.md).
