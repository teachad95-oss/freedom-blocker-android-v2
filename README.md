# Freedom Blocker Android v2

A **native Android (Java)** website blocker that prevents access to distracting
sites in Chrome, Brave, and Edge during user-defined sessions — with a hardened
watchdog that makes the session nearly impossible to bypass.

---

## Features

| Feature | Details |
|---|---|
| **Keyword blocking** | Monitors Chrome, Brave & Edge URL bars via Accessibility Service; blocks if URL contains any blocked keyword |
| **Overnight sessions** | e.g. 8 AM → 3 AM next day — automatically handled |
| **Irrevocable sessions** | UI locked during session; service cannot be stopped via the app |
| **App lock** | Device Administrator prevents uninstall during active sessions |
| **Watchdog** | `START_STICKY` + `JobScheduler` (15 min) + `BOOT_COMPLETED` receiver keep the service alive |

---

## Architecture

```
MainActivity              → UI (keyword list, session picker, permission setup)
SessionManager            → Persists session start/end epoch in SharedPreferences
KeywordStore              → Persists keyword list in SharedPreferences
BlockingAccessibilityService → Monitors browser URL bars, triggers blocks
FreedomForegroundService  → Persistent foreground service, session expiry check loop
WatchdogJobService        → JobScheduler job — restarts service every 15 min if needed
WatchdogReceiver          → Restarts on BOOT_COMPLETED, package replace, internal signal
FreedomDeviceAdminReceiver→ Prevents uninstall; warns if admin revoked during session
BlockOverlayActivity      → Full-screen block card shown when keyword matched
```

---

## Required Permissions (User Must Grant)

1. **Accessibility Service** — Settings → Accessibility → Freedom Blocker → Enable
2. **Device Administrator** — prompted by the app on first launch
3. **Draw Over Other Apps** — prompted by the app

---

## How to Build

### Cloud (GitHub Actions — recommended)
1. Push this folder to GitHub.
2. Go to **Actions → Build Freedom Blocker APK → Run workflow**.
3. Download `freedom-blocker-debug.apk` from the workflow artifacts.

### Local (Android Studio)
1. Open `freedom_blocker_android_v2/` in Android Studio (Hedgehog or newer).
2. Connect a device / start emulator.
3. **Run → Run 'app'** or `./gradlew installDebug`.

---

## First-Time Setup on Device

1. Install APK.
2. Open **Freedom Blocker**.
3. Tap **Grant** next to each permission (3 total).
4. Add keywords (e.g. `youtube`, `reddit`, `twitter`).
5. Pick a **Start** and **End** time.
6. Tap **Start Session** — the blocking session begins (or is scheduled).

---

## Session Lifecycle

```
┌─ User taps Start Session ──────────────────────────────────────────┐
│  SessionManager.startSession(startMs, endMs)                       │
│  FreedomForegroundService starts (persistent notification)         │
│  WatchdogJobService scheduled (every 15 min)                       │
│  Device Admin active → uninstall blocked                           │
└────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌─ During session ───────────────────────────────────────────────────┐
│  AccessibilityService: URL event → keyword match → BLOCK           │
│  ForegroundService: polls every 2 s, updates notification           │
│  If app killed → START_STICKY relaunches it                        │
│  If still dead → WatchdogReceiver restarts it                      │
│  If still dead → JobScheduler fires in ≤15 min                     │
└────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌─ Session expires ──────────────────────────────────────────────────┐
│  ForegroundService detects expiry → clearSession() → stopSelf()    │
│  Notification dismissed                                            │
│  UI unlocks (keywords editable again)                              │
└────────────────────────────────────────────────────────────────────┘
```

---

## Project Structure

```
app/src/main/
├── java/com/freedom/blocker/
│   ├── MainActivity.java
│   ├── SessionManager.java
│   ├── KeywordStore.java
│   ├── KeywordAdapter.java
│   ├── BlockingAccessibilityService.java
│   ├── FreedomForegroundService.java
│   ├── WatchdogJobService.java
│   ├── WatchdogReceiver.java
│   ├── FreedomDeviceAdminReceiver.java
│   └── BlockOverlayActivity.java
├── res/
│   ├── layout/ (activity_main.xml, activity_block_overlay.xml, item_keyword.xml)
│   ├── drawable/ (button shapes, chip bg, dot indicator)
│   ├── values/ (colors.xml, themes.xml, strings.xml)
│   └── xml/ (accessibility_service_config.xml, device_admin.xml)
└── AndroidManifest.xml
```
