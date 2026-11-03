# Remote access status — v1.8

This build does **not** provide live remote screen viewing or remote touch control yet. The new in-app Remote Access status panel explains this rather than implying that a pairing code or remote session already works.

A real Android-to-Android implementation still requires:

1. An authenticated pairing/signaling backend with short-lived, single-use pairing codes.
2. Encrypted WebRTC video/data transport, STUN and (where needed) TURN relay infrastructure.
3. Android MediaProjection started only after the device owner approves the system capture prompt.
4. A visible foreground session notification, explicit session acceptance, and a reliable stop/disconnect action.
5. Accessibility-based remote gestures only after the owner separately enables the service; Android/OEM restrictions still apply.

Do not use hidden capture, silent installation, bypassed consent, or unattended access. A link by itself cannot grant access to another phone. No signaling/TURN server credentials are bundled in this project.

## Current functionality retained

- Voice and text commands for supported local actions.
- Optional foreground wake-phrase service (speech-service and battery limitations apply).
- Optional Accessibility text entry into a focused editable field; it does not press Send.
- Saved-contact WhatsApp chat opening and manual-send workflow.

## Build

GitHub Actions workflow `.github/workflows/main.yml` builds a debug APK and uploads it as the `JARVIS-debug-APK` artifact. A successful workflow run is required before treating the APK as built.
