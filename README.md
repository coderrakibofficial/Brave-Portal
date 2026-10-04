# BRAVE Portal – Android app

A thin native Android container (Kotlin + WebView) around
**https://brave.coachkanchon.academy/**. The website stays the real product – the app only adds:
icon, splash, loading screen, offline screen, back-button handling, fullscreen video, downloads,
uploads and external-link handling.

**Why not Capacitor?** Capacitor would only wrap the same Android WebView, and every critical feature
here (fullscreen video, downloads, back button, popups, file picker) needs custom native code anyway.
A plain WebView app has fewer tools to install (no Node.js), fewer moving parts and is easier to maintain.

---
## 1. Where to change things (one place!)

| What | Where |
|---|---|
| WEBSITE_URL, APP_NAME, PACKAGE_ID (`APP_ID`), APP_VERSION (`VERSION_NAME`/`VERSION_CODE`) | **`brave.properties`** (project root) |
| Extra hosts that must stay inside the app (e.g. a separate login domain) | `EXTRA_INTERNAL_HOSTS` in `brave.properties` |
| Icon / splash / loading logo | run `make_icons.py` (see §10) or replace files listed in §10 |
| Colors | `app/src/main/res/values/colors.xml` |
| Texts (loading / offline messages) | `app/src/main/res/values/strings.xml` |
| All app behaviour | `app/src/main/java/com/brave/portal/MainActivity.kt` |

---
## 2. Required software
1. **Android Studio** (latest stable, "Ladybug" or newer) – https://developer.android.com/studio
   It already includes JDK 17 and the Android SDK. In Android Studio: *Settings → SDK Manager* → install **Android SDK Platform 35**.
2. **Node.js is NOT required.**
3. (Optional, only for regenerating icons) Python 3 + `pip install pillow numpy`.

## 3–4. Open and run
1. Unzip the project.
2. Android Studio → **Open** → select the `BravePortal` folder.
3. Wait for "Gradle sync" to finish (first time downloads ~500 MB; needs internet).

## 5. "Sync" 
Equivalent of `cap sync`: click **File → Sync Project with Gradle Files**. Do this after editing `brave.properties`.

## 6–7. Test with the emulator
1. *Device Manager → Create device* (e.g. Pixel 7, API 34).
2. Press the green **Run ▶** button.

## 8. Run on your physical phone
1. Phone: *Settings → About phone →* tap **Build number** 7 times, then *Developer options → USB debugging* ON.
2. Connect with USB, accept the prompt on the phone, choose the phone in Android Studio and press **Run ▶**.

---
## 9–10. Debug APK
Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)** → click "locate" in the popup.
Or terminal (Mac/Linux; on Windows use `gradlew.bat`):
```bash
./gradlew assembleDebug
```
File: `app/build/outputs/apk/debug/app-debug.apk` – copy it to a phone and install it.

## 13. Create a keystore (once – BACK IT UP, you cannot publish updates without it)
```bash
keytool -genkey -v -keystore brave-release.jks -alias brave -keyalg RSA -keysize 2048 -validity 10000
```
Keep `brave-release.jks` **outside** the project (or it stays git-ignored) and remember both passwords.

## 14. Tell the project how to sign
Copy `keystore.properties.example` to `keystore.properties` and fill it in:
```properties
storeFile=/full/path/to/brave-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=brave
keyPassword=YOUR_KEY_PASSWORD
```
This file is git-ignored; no secret is ever stored in the project.

## 11. Release APK (signed, for direct installation)
```bash
./gradlew assembleRelease
```
File: `app/build/outputs/apk/release/app-release.apk`

## 12. Release AAB (for Google Play)
```bash
./gradlew bundleRelease
```
File: `app/build/outputs/bundle/release/app-release.aab` → upload in Play Console.
For every new upload, raise `VERSION_CODE` in `brave.properties`.

---
## 15–16. Icon and splash screen
Everything is generated from the logo in `store-assets/brave_source.png`:
```bash
pip install pillow numpy
python3 make_icons.py store-assets/brave_source.png
```
It rewrites: `mipmap-*/ic_launcher*.png` (legacy + adaptive foreground + themed monochrome),
`drawable-nodpi/splash_icon.png` (system splash), `drawable-nodpi/brave_logo.png` (loading/offline screens)
and `store-assets/play_store_icon_512.png` (upload to Play Console).
Adaptive background colour: `brave_black` in `colors.xml`.
(Android Studio alternative: right-click `res` → *New → Image Asset*.)

## 17. Change the website URL
Edit `WEBSITE_URL` in `brave.properties` (must be `https://`), then sync Gradle.

## 18. Change the package name
Edit `APP_ID` in `brave.properties`, sync. Nothing else is needed (source folders keep `com.brave.portal`
internally; that is invisible to users and Google Play).

---
## How the important behaviours work
* **Login persistence:** cookies, localStorage, sessionStorage are enabled and cookies are flushed to disk on pause/stop, so the session survives app restarts. The server decides when it expires. The app stores no passwords.
* **No reloads:** `configChanges` in the manifest stops Activity recreation on rotation; `onPause/onResume` keep the page; if Android kills the process the last page is restored.
* **Back button:** exits fullscreen video first → then `webView.goBack()` → exits only when no history remains.
* **Video:** `onShowCustomView` hosts the site's own player fullscreen (landscape allowed, system bars hidden); the lesson page is only hidden, not reloaded. Portrait is restored on exit.
* **Links:** `https://brave.coachkanchon.academy/*` stays inside; other https/http, `mailto:`, `tel:`, `sms:`, `geo:`, WhatsApp, `intent:` open in the matching app. Popups / `target=_blank` are routed the same way. Embedded iframes (video players) load normally.
* **Downloads:** Android `DownloadManager` with the session cookie, saved to *Downloads*, with notification. No permission on Android 10+ (Android 9 and older ask once).
* **Uploads:** Android system file picker; no camera/storage permission declared.
* **Offline:** branded screen with Retry; auto-retries when the network returns. Real server errors (404/500) are shown by the website itself.
* **Security:** HTTPS only (cleartext off), SSL errors are never bypassed, mixed content blocked, file access off, no JS injection, no JS bridge.
* **Deep links:** `https://<portal host>/…` is registered. For links to open in the app *automatically* (without a chooser), host an `assetlinks.json` on the website later (needs your release key's SHA-256: `keytool -list -v -keystore brave-release.jks`).
* **Pull-to-refresh:** intentionally not added (could reload a lesson/video by accident).

## Known limitations (website-related)
1. **Login through an outside provider** (Google/Facebook/payment on another domain): such pages open in the external browser and cannot return the session. Fix: add that domain to `EXTRA_INTERNAL_HOSTS` (Google blocks sign-in inside WebViews, so for Google login the site would need an email/password login or a small website-side change).
2. **Downloads created by JavaScript (`blob:` links)** can't use DownloadManager; the app shows a message. Normal file links work.
3. **Some video platforms** refuse to play in any WebView; test your actual lessons (see checklist).
4. Camera capture inside file inputs and website camera/mic use are not enabled (no permissions requested).

---
## Testing checklist (do on a real phone)
- [ ] Installs; icon + name "BRAVE Portal" correct; splash then branded loading (no white flash)
- [ ] Login works; close app (swipe away) → reopen → still logged in (unless server expired it)
- [ ] Dashboard, menus, classes, lessons open; back button walks history, exits only at the start
- [ ] Video plays, seeks, has audio; fullscreen → landscape → exit → portrait; lesson not reloaded; back in fullscreen only exits fullscreen
- [ ] Forms + keyboard (fields stay visible), password autofill
- [ ] Download a PDF/ZIP; upload a file (if the portal has uploads)
- [ ] External link, mailto, tel, WhatsApp, new-window links
- [ ] Airplane mode → offline screen → turn network on → auto/Retry works
- [ ] Lock phone / switch apps / return: same page
- [ ] `./gradlew assembleDebug` and `./gradlew bundleRelease` succeed

---
## Update 1.1 – native improvements
* **Push notifications (FCM):** see **[FIREBASE_SETUP.md](FIREBASE_SETUP.md)**. WordPress plugin: `wordpress/brave-fcm-notifier/`.
* **Pull-to-refresh** (long pull at top of page; switch off with `PULL_TO_REFRESH=false` in `brave.properties`).
* **Server errors (HTTP 5xx)** now show the branded retry screen, like offline/DNS errors.
* **Uploads:** gallery/files *and* camera photo (no CAMERA permission needed).
* **Notification tap** opens the portal page sent with the notification.
* **CI:** GitHub Actions builds on `main`, `feature/**` and manual runs → artifact **BRAVE-Portal-APK**. The optional secret `GOOGLE_SERVICES_JSON` enables push.
