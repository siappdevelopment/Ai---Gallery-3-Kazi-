---
name: unlock_ad_logic
description: Full A-to-Z logic and Remote Config (RC) of the "App Open ad after the phone is unlocked" feature as built in the AIGallery project (F:\Muntazim\AI_Gallery\AIGallery). Use when implementing, porting, fixing or debugging the unlock ad (Unlock_App_Open_*) in any launcher-style app, or when the unlock ad does not show after the app is set as default Home.
---

# unlock_ad_logic — App Open ad after phone unlock (reference: AIGallery)

Source of truth (AIGallery, package `com.photogallery.album.manager`):
`ads/UnlockAppOpenAds.kt` (the whole feature), `ads/SwipeSequenceAds.kt` (sequence, `Side.UNLOCK`), `ads/AdConfig.kt` (RC fields),
`ads/AdState.kt` (per-day gap), `ads/AdManager.kt` (`FullCache`, `showAppOpenCached`), `activity/LauncherHomeActivity.kt`
(`onResume` / `onPause` hooks), `GalleryApp.kt` (`UnlockAppOpenAds.start(this)`), `utils/DefaultLauncher.kt`.

## 1. The core idea (why it works there)

The ad is **not** shown by starting a new activity from the background. The app is the default Home app, so after an unlock
the system puts **the launcher Home activity itself in front**. The ad is shown **on that already-visible activity**
(`LauncherHomeActivity`), so no "background activity start", no overlay permission, no transparent host screen is needed.

The flow is a small state machine in `object UnlockAppOpenAds`:

```
Application.onCreate -> UnlockAppOpenAds.start(app)   // runtime receiver: SCREEN_OFF, SCREEN_ON, USER_PRESENT
SCREEN_OFF   -> onLocked():   armed = canRun();  unlockedAtMs = 0;  if (armed) preload()
SCREEN_ON    -> watchKeyguard(): poll isKeyguardLocked every 300 ms (max 60 s)  -> keyguard gone => onUnlocked()
USER_PRESENT -> onUnlocked():  if (armed) { unlockedAtMs = now; tryShow() }
Home.onResume-> onHomeResumed(activity): remember activity; if armed && !unlocked && !keyguardLocked => treat as unlocked; tryShow()
Home.onPause -> onHomePaused(activity): forget activity
tryShow(): needs armed + unlockedAtMs != 0 + Home in front, all within a 6 s window (UNLOCK_WINDOW_MS)
           - window expired            -> armed = false (no ad)
           - gallery page is open      -> armed = false (ResumeAppOpen handles that page, so never two ads)
           - else armed = false; show(activity)
show(): re-check canRun() and !AdManager.isFullScreenBusy, AdState.markShown(KEY_UNLOCK) BEFORE the ad,
        then sequence (if configured) else single cached App Open ad.
```

Key design points:
- **Arm on lock, not on unlock.** The gates are evaluated and the ad is **preloaded while the screen is off** (only if internet is on), so at unlock the ad is ready and shows instantly.
- **Three unlock signals** because phones differ: `USER_PRESENT`, keyguard disappearing (poll after `SCREEN_ON`), and Home resuming while the keyguard is already gone (face unlock / no lock screen / battery savers that drop `USER_PRESENT`). Home resume and the broadcast can arrive in either order, hence the 6 s window.
- **The per-day slot is saved before the ad shows** (a failed request also uses its slot).
- **Gallery page excluded** (`LauncherHomeActivity.isGalleryPageOpen()`): that page uses the normal resume App Open ad. Unlock ad = wallpaper / report / other launcher pages only.
- **Only when this app is the default Home app** (`DefaultLauncher.isDefault`: `RoleManager.ROLE_HOME` held on Android 10+, else `resolveActivity(HOME)`).
- Receiver is a **runtime** receiver (`ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`); `USER_PRESENT`/`SCREEN_*` cannot be manifest receivers. It lives as long as the process (a launcher process normally stays alive).
- `onConfigApplied()` (called when a new RC is applied) clears the preloaded ad because ids may have changed.

## 2. `canRun()` gates (all must pass, in this order)

1. `Unlock_App_Open_Show == true` **and** `Unlock_App_Open_Per_Day > 0` (0 = never).
2. App is the default Home app **and** internet is available.
3. Country gate: `Unlock_Ad_Show_Country` (empty = everywhere) **and** `Unlock_Ad_Show_Exclude_Location` (exclude always wins). The **global** `Ads_Show_countryList` / `Ads_show_List_Exclude` do NOT apply to this ad. IP place refreshed on lock (`QuizAds.refreshCountryIfStale("phone locked")`).
4. An ad id exists: `Unlock_App_Open_Id` (blank -> `App_Open_Id`), unless a sequence is configured (it brings its own ids).
5. Gap: `AdState.canShow(KEY_UNLOCK, perDay)` -> now - last >= 24 h / perDay (a last-show time in the future counts as "long ago").

`whyNot()` returns the human reason for the log.

## 3. Remote Config (path `Screen.LauncherAppScreen`)

`_Show`, `_Per_Day`, `_Id`, `Unlock_Ad_Show_Country`, `Unlock_Ad_Show_Exclude_Location` fall back to the **profile root** when missing in the block; all others are read from the block only.

| Key | Type | Meaning |
|---|---|---|
| `Unlock_App_Open_Show` | bool | master switch |
| `Unlock_App_Open_Per_Day` | int | max per day; gap = 86400 / N seconds; 0 = never (e.g. 5760 = every 15 s) |
| `Unlock_App_Open_Id` | string | App Open unit id; blank -> `App_Open_Id` |
| `Unlock_App_Open_Ads_type` | `"preload"` / other | `preload` = load the ad when the phone locks; default `preload` |
| `Unlock_App_Open_Ad_Sequence` | `[[type,count],...]` | types: `Google_App_Open`, `Google_Inter`, `Google_Native`, `Quiz_Inter`, `Quiz_App_Open`, `Quiz_Native`, `Quiz_Browser`; replaces the single ad |
| `Unlock_App_Open_Interstitial_Id` | string | for `Google_Inter` step; blank -> `inter_ads_id` |
| `Unlock_App_Open_Native_Id` | string | for `Google_Native` (full-screen native) step |
| `Unlock_App_Open_Failed_Show_Quiz` | bool (default true) | failed Google step shows Quiz instead |
| `Unlock_Ad_Show_Country` | list | only these places (empty = all) |
| `Unlock_Ad_Show_Exclude_Location` | list | never these places |

Sample:
```json
"Unlock_App_Open_Show": true, "Unlock_App_Open_Id": "ca-app-pub-.../9257395921", "Unlock_App_Open_Per_Day": 5760,
"Unlock_App_Open_Ads_type": "preload",
"Unlock_App_Open_Ad_Sequence": [["Google_App_Open",1],["Google_Inter",1],["Google_Native",1]],
"Unlock_App_Open_Interstitial_Id": "ca-app-pub-.../1033173712", "Unlock_App_Open_Native_Id": "ca-app-pub-.../2247696110",
"Unlock_App_Open_Failed_Show_Quiz": true, "Unlock_Ad_Show_Country": [], "Unlock_Ad_Show_Exclude_Location": []
```

## 4. Sequence (`SwipeSequenceAds`, `Side.UNLOCK`)

- Steps are expanded by count; a **saved cursor** picks the step and advances on every attempt (even if it fails); the cursor restarts when the sequence text changes. Google steps with a blank id are skipped; Quiz steps need no id.
- `Ads_type = preload` loads the Google ad of the **next** step ahead (inter / app open; full native loads on demand) and again after each show. Not under QUIZ priority.
- Unlock uses its own location lists (`ownLocation(UNLOCK) = true`) so the global country lists are skipped.
- No sequence -> single ad: `AdManager.showAppOpenCached(activity, unitId(), cache, quizOnFail = googleFailQuiz, honorQuizPriority = false, ignoreLocation = true)`: preloaded ad if present, else one request with no dialog. Quiz priority does not replace the single unlock ad (use a `Quiz_Browser` step or the fail-over flag).
- `FullCache`: one ad, handed out once, valid 55 min, never two requests in flight, nothing requested under QUIZ priority.

## 5. Log lines (tag `KaziAdFlow`, prefix `Unlock ad:`)

`broadcast <action>` · `phone locked, armed=<bool> (<reason>)` · `phone unlocked, armed=.., home in front=..` · `Home resumed, armed=.., keyguard locked=..` · `the keyguard is gone` · `Home is back and the phone is unlocked (no USER_PRESENT needed)` · `the Home page did not come in front in time` · `the gallery page is in front, the return ad handles it` · `showing, next one after N s` · `not shown (<reason>)`.

## 6. Porting / fixing checklist for another app (e.g. ThirdGallery)

ThirdGallery today (`ads/LauncherAds.kt` `UnlockAds` + `ads/AdHostActivity.kt`) does the opposite: on `USER_PRESENT` only it **starts a transparent `AdHostActivity` from the background** (`EventLauncher.startScreen`) and loads the ad on demand (10 s timeout), and it **ignores** `Unlock_App_Open_Ads_type`. That path is fragile: OEMs (Xiaomi/Realme/Oppo) block background activity starts, `USER_PRESENT` is not sent by every phone, and nothing is preloaded. To get the AIGallery behaviour:

1. Add `start(app)` in `Application.onCreate`: runtime receiver for `SCREEN_OFF`, `SCREEN_ON`, `USER_PRESENT`.
2. On `SCREEN_OFF`: evaluate gates -> `armed`; if armed and `unlockPreload`, preload the next Google step into a `FullCache`.
3. On unlock signals (3 of them, section 1) set `unlockedAtMs`; on **launcher Home `onResume`/`onPause`** call `onHomeResumed(activity)` / `onHomePaused(activity)`.
4. Show the ad **on the visible launcher Home activity** (no new activity, no overlay permission). Keep the 6 s window and the "gallery page = resume ad" exclusion.
5. Write the per-day timestamp before showing; keep sequence cursor logic and Quiz fail-over.
6. Gate on default Home (`isHomeAppOrRoleHolder`), internet, own country lists, id, gap, and `!isFullScreenBusy`.
7. Keep the `Unlock ad:` logs - they tell exactly which gate stopped the ad.

Common reasons "launcher set but no ad": per-day/gap not over; `Unlock_App_Open_Show` false or `Per_Day` 0; no internet at lock time (no preload, no gate pass); country lists; Home not in front within 6 s; gallery page open; another full-screen ad busy; process killed (receiver gone); OEM blocking background start (only in the host-activity design).
