# Jarvis: your own voice assistant for Android

One app, everything included: animated UI, voice in/out, free AI brain, phone actions,
hands-free "Jarvis" wake word, and registration as the phone's default digital assistant.

## Build and install (about 10 minutes, all free)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. **File > Open** and pick this `jarvis-assistant` folder. Let Gradle sync (first time downloads ~1 GB).
3. On your phone: Settings > About phone > tap *Build number* 7 times, then
   Developer options > turn on **USB debugging**. Plug the phone in.
4. Press the green **Run** button. Jarvis installs and opens.
   (To get a shareable file instead: **Build > Build APK(s)**.)

## First launch (the Setup screen walks you through it)

| Step | What | Cost |
|---|---|---|
| 1 | Grant mic / contacts / phone permissions | free |
| 2 | Paste a **Gemini** key (https://aistudio.google.com/apikey) or **Groq** key (https://console.groq.com/keys) | free |
| 3 | Paste a **Picovoice AccessKey** (https://console.picovoice.ai) for the "Jarvis" wake word | free tier |
| 4 | Settings > choose Jarvis as default assistant; allow "display over other apps" | free |

Without a key, calls, texts, alarms, timers, apps, flashlight, volume, time and battery
still work (offline rules). The key adds questions, chat and flexible phrasing.

## Things to say

- "Call Mom" / "Text Rahul saying I'll be late" / "WhatsApp Priya saying hi"
- "Set an alarm for 6:30 am" / "Set a timer for 10 minutes"
- "Open Instagram" / "Play Arijit Singh" / "Play lo-fi on YouTube"
- "Navigate to the airport" / "Search for best pizza near me"
- "Turn on the flashlight" / "Volume up" / "Open Wi-Fi settings"
- "What's my battery?" / "What time is it?" / anything else goes to the AI

## Honest limits

- **Texts** open the message ready to send (you tap Send), a deliberate safety choice so a
  mis-heard command never sends something by itself.
- Android blocks apps from toggling Wi-Fi / Bluetooth silently, so Jarvis opens the right settings page.
- Android 14+ doesn't let microphone services auto-start at boot. After a restart, open Jarvis once and
  hands-free resumes.
- The wake word is the single word **"Jarvis"** (Picovoice's built-in keyword).
- Free API tiers have rate limits; Jarvis will tell you if you hit one.
- Settings screens for "default assistant" differ a little between phone brands.

## Project map

- `core/Assistant.kt` listen > think > act > speak engine (shared by every trigger)
- `core/Brain.kt` offline rules + Gemini/Groq
- `core/Actions.kt` calls, SMS/WhatsApp, alarms, apps, music, maps, flashlight, volume, settings
- `service/WakeWordService.kt` hands-free "Jarvis" (Porcupine)
- `system/AssistantServices.kt` default digital assistant registration
- `ui/` orb animation, main screen, setup screen, transitions
