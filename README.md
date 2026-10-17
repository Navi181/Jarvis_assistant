# Jarvis

A free voice assistant for Android: voice and typed commands, calls, texts, WhatsApp, alarms, apps, music,
live answers, photo search, a Study space and a locked focus timer. Built and updated entirely from a phone.

## Getting the app (phone only)
1. Upload `jarvis-assistant.zip` to this repository (Add file, Upload files, Commit). It unpacks and builds by itself.
2. When the build shows a green tick, open **Releases**, tap **Jarvis build ...**, and download **Jarvis.apk**.
3. Open the file and install it. If Play Protect or Auto Blocker blocks it, switch the scan or blocker off for the install.
4. Later updates: open Jarvis, Setup, **Check for updates now**.

## First run
- Sign in by choosing an account on the phone.
- Setup: grant permissions, add a free Gemini or Groq key (add both for automatic backup), download the wake-word model on Wi-Fi.
- Optional: Claude key (paid), Accessibility access (WhatsApp auto-send and focus lock), default assistant, notification access.

## Notes
- The signing key lives in the repository secrets `JARVIS_KEYSTORE_B64` and `JARVIS_KS_PASS`. Every build must use the same key.
- A weekly build re-checks the code against library changes.
- Progress and the block list are in `STATUS.md`.
