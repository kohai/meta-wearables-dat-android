# Open WebUI Bridge Sample

This sample Android app demonstrates a bridge between Meta Wearables DAT camera access and an
Open WebUI server. It streams a frame from Meta glasses, JPEG-encodes the current preview frame,
uploads snapshots through Open WebUI's files API, and sends them to Open WebUI's chat-completions
API with a user prompt.

## What it demonstrates

- DAT app registration and camera permission flow
- Glasses camera streaming through `mwdat-camera`
- MockDeviceKit testing support inherited from the CameraAccess sample
- A minimal Open WebUI API client using chat sessions and `POST /api/v1/chat/completions`
- Vision-style chat-completions payloads with a base64 JPEG data URL and Open WebUI file metadata
- A configurable system prompt that tells the model it is responding through Meta glasses
- Copy, share, and Android text-to-speech playback for assistant responses

## Prerequisites

- Android Studio
- JDK 11 or newer
- Android SDK 31+
- GitHub personal access token with `read:packages` for DAT SDK dependencies
- Open WebUI reachable from the Android device or emulator
- An Open WebUI API key from Settings > Account
- A vision-capable model configured in Open WebUI

## Building

1. Open `samples/OpenWebUIBridge` in Android Studio.
1. Add `github_token=<your token>` to `samples/OpenWebUIBridge/local.properties`, or set
   `GITHUB_TOKEN` in your environment.
1. Sync Gradle and run the `app` configuration.

## Running

1. Enable Developer Mode for your glasses in the Meta AI app.
1. Launch the sample and connect through the Meta AI registration flow.
1. The bridge starts automatically after an active device appears. Use **Stop bridge** to return to
  device selection when needed.
1. Enter:
  - Open WebUI API endpoint, for example for an emulator talking to the host machine
   - API key
  - model id as it appears in Open WebUI, or tap **Load models** and choose one from the list
  - system prompt
  - prompt
1. Tap **Start camera** when you want the camera stream active.
1. Tap **Snapshot ask** or **Voice ask** to capture a frame and send it to Open WebUI.
1. Use **Copy**, **Share**, or **Speak** on the response when it returns.

The app allows cleartext HTTP because local Open WebUI development commonly uses `http://` LAN or
emulator URLs. Use HTTPS and stricter network security settings for a production app.

## Notes

- The sample stores the Open WebUI endpoint, API key, selected model, system prompt, and prompt in
  app-private local preferences so they are available the next time you open the bridge.
- The app follows the Android system light/dark setting by default. Use **Theme** in settings to
  override it to light or dark mode.
- Each ask is attached to an Open WebUI chat session so follow-up questions can use the prior
  conversation. Use **New chat** in settings to start a fresh server-side conversation.
- Snapshot asks upload the JPEG to `/api/v1/files/` and attach the returned file metadata to the
  user message so the image appears in Open WebUI chat history.
- The selected Open WebUI model must support image input. Text-only models will not analyze the
  frame correctly.
- Response playback uses Android `TextToSpeech` routed toward the current Bluetooth communication
  device when available. The public DAT SDK does not expose Meta AI's built-in voice TTS as an app
  API.
- This app is a bridge sample; it does not intercept the built-in Meta AI assistant experience on
  the glasses.

## License

This source code is licensed under the license found in the LICENSE file in the root directory of
this source tree.
