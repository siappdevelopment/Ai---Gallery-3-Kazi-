---
name: app_icon_ad
description: Full A-to-Z logic, Remote Config (RC), assets and res of the "game icon ads" of the launcher as built in AIGallery (F:\Muntazim\AI_Gallery\AIGallery) - LauncherApp_Quiz_Icon_Show, LauncherApp_Quiz_Icon_Count and LauncherApp_Game_Folder_Show. 40 bundled game icons with an "Ad" badge that open a game link in the browser, mixed into the app drawer and/or filling the two Home folders. Use to implement, port or debug these icons in a launcher app (e.g. ThirdGallery).
---

# app_icon_ad - game icon ads in the launcher (reference: AIGallery)

Package of the reference app: `com.photogallery.album.manager`. Bundled copies of the key source files are in
`assets/source/`, and the 40 icon PNGs in `assets/drawable-nodpi/` (copy to `app/src/main/res/drawable-nodpi/` as-is,
names `ic_qz_icon_1.png` .. `ic_qz_icon_40.png`, about 3.5 MB total).

## 1. What the feature is

A "Quiz game" ad is a fake app icon: it looks like an app in the launcher, shows a small golden **"Ad"** badge, and a tap opens a
game web page in the browser (`https://gamqora.com/topgames/<Name>/index.html`) - it never starts an installed app.
There are 40 built-in games. Two RC switches place them:

1. **Drawer icons** (`LauncherApp_Quiz_Icon_Show` + `LauncherApp_Quiz_Icon_Count`): inside the all-apps drawer, one game icon is
   inserted right after each of the first N apps.
2. **Home folders** (`LauncherApp_Game_Folder_Show`): the two Home folders (normally "Google" and "Tools") hold the games instead:
   folder 1 = games 1-20 ("Games"), folder 2 = games 21-40 ("More games").

## 2. Remote Config (path `Screen.LauncherAppScreen`)

| Key | Type | Meaning |
|---|---|---|
| `LauncherApp_Quiz_Icon_Show` | bool | master switch of the drawer icons; false = none |
| `LauncherApp_Quiz_Icon_Count` | int | how many game icons the drawer shows, clamped to 0..40 (`LauncherQuizGames.MAX_GAMES`); sample value 20 |
| `LauncherApp_Game_Folder_Show` | bool | true = the two Home folders show the games instead of the Google / Tools folders |

Reading order (`utils/GameAdsConfig.kt`): the ad-config JSON block wins when it **has the key** (`launcher.quizIconShow` etc. are
nullable: `null` = key missing); otherwise the flat Firebase Remote Config key of the same name; otherwise the in-app default
(`icon show = true`, `count = 5`, `folder show = true`). Firebase values are used only when `source == VALUE_SOURCE_REMOTE`.
`quizIconCount` is `0` when `quizIconShow` is false. Any exception falls back to the default, so the launcher never depends on it.
New values apply the next time Home / the drawer render (no restart needed). `GameAdsConfig.init()` does a
`fetchAndActivate()` and is held back while a VPN is on (`resumeAfterVpn` runs it later).

Parsing (`ads/AdConfig.kt`, inside `LauncherAppCfg`):
```kotlin
quizIconShow   = if (la?.has("LauncherApp_Quiz_Icon_Show") == true) la.bool("LauncherApp_Quiz_Icon_Show") else null,
quizIconCount  = if (la?.has("LauncherApp_Quiz_Icon_Count") == true) la.int("LauncherApp_Quiz_Icon_Count") else null,
gameFolderShow = if (la?.has("LauncherApp_Game_Folder_Show") == true) la.bool("LauncherApp_Game_Folder_Show") else null,
```
Sample RC: `"LauncherApp_Quiz_Icon_Show": true, "LauncherApp_Quiz_Icon_Count": 20, "LauncherApp_Game_Folder_Show": true`.

## 3. Code pieces (all copied in `assets/source/`)

- `model/LauncherApp.kt` - `LauncherApp(name, packageName, icon, installTime, gameUrl: String? = null)`. **`gameUrl != null` is what
  makes a row a game ad** (not an installed app).
- `utils/LauncherQuizGames.kt` - `QuizGame(title, url, iconRes)`; `object LauncherQuizGames`: `MAX_GAMES = 40`,
  `GAMES_PER_FOLDER = 20`, the ordered list `games` (40 entries; order = display order), `apps(context)` (builds `LauncherApp` rows once,
  cached; `packageName = "quiz_game_<n>"`, `name = title`, `gameUrl = url`; a missing drawable is skipped) and `openLink(context, url)`
  (`ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`, never throws).
- `utils/GameAdsConfig.kt` - the RC reader described above (`quizIconShow`, `quizIconCount`, `gameFoldersShow`).
- `res/layout/item_launcher_app.xml` + `res/drawable/bg_launcher_ad_badge.xml` - the drawer / folder cell with the `tvAd` badge
  (golden pill `#FFF5B82E`, radius 4sdp, text `@string/launcher_quiz_ad_label`, `visibility=gone` by default).

### Drawer (`views/LauncherAppsDrawer.kt`)
- `showResults(list)` -> `withGameIcons(list)`: if the list is empty or a **search is typed -> no games**. Otherwise
  `count = GameAdsConfig.quizIconCount; games = LauncherQuizGames.apps(ctx).take(count)` and the result is built as
  `apps[0], games[0], apps[1], games[1], ...` (a game right after each of the first N apps). Read from RC every time the list is shown.
- `launch(app)`: `app.gameUrl?.let { AppAnalytics.log("quiz_game_icon_click", "slot" to "drawer"); LauncherQuizGames.openLink(ctx, it); return }`
  - so a game tap skips the app-tap interstitial, the recents store and the "return ad" bookkeeping.
- Adapter (`adapter/LauncherAppsAdapter.kt`): `tvAd.visibility = if (app.gameUrl != null) VISIBLE else GONE`; the badge is moved
  inside the rounded icon corner: `inset = size * ICON_CORNER_RATIO(0.4) * BADGE_INSET_OF_RADIUS(0.34)` as `topMargin`/`marginEnd`;
  **no long-click menu for a game** (`longClick` only when `gameUrl == null`).
- Icons are rendered through `LauncherIconCache` (cache key `packageName@size`, so `quiz_game_<n>` keys never clash with real apps).

### Home folders (`fragment/LauncherHomeFragment.kt`)
- `loadFolderApps()` clears `googleApps` / `toolsApps`, then if `GameAdsConfig.gameFoldersShow`:
  `games = LauncherQuizGames.apps(ctx)`; `googleApps += games.take(20)`; `toolsApps += games.drop(20).take(20)`;
  folder titles = `launcher_folder_games` ("Games") and `launcher_folder_more_games` ("More games"). Else the normal Google / Tools
  apps and titles. Then `bindFolderPreview` (3 columns, max 9 icons per folder preview) and a folder is hidden when its list is empty.
- `showFolder()` dialog grid (3 columns): a tap on a game -> `AppAnalytics.log("quiz_game_icon_click", "slot" to "folder")` +
  `openLink`; a normal app -> `withAppTapAd { startResolved(intent) }`.
- `loadFolderApps()` is called on view creation and again after an app install / uninstall refresh.

## 4. Strings

`launcher_quiz_ad_label` = "Ad" (`translatable="false"` in the default file; some locale files translate it, e.g. fr "Pub", ko "광고"),
`launcher_folder_games` = "Games" (hi "गेम", fr "Jeux"), `launcher_folder_more_games` = "More games" (hi "और गेम", fr "Plus de jeux").
Add the three to `values/strings.xml` and translate the two folder titles for the app's languages.

## 5. Port checklist (e.g. ThirdGallery)

ThirdGallery already parses the three keys (`ads/AdModels.kt` lines ~379-381: `quizIconShow`, `quizIconCount`, `gameFolderShow`, as plain
non-null values) but nothing uses them. To add the feature:
1. Copy `assets/drawable-nodpi/*` to `res/drawable-nodpi/`; add the 3 strings and `bg_launcher_ad_badge.xml`.
2. Add `gameUrl: String? = null` to the launcher app model; add `LauncherQuizGames.kt` (adapt package / `R`); keep the 40 titles and urls.
3. RC: nullable fields (null = key missing) or keep ThirdGallery's non-null parse; clamp count 0..40; `count = 0` when the switch is off.
4. Add the `tvAd` badge to the app cell; show it only for `gameUrl != null`; no long-click for games.
5. Drawer: insert `games[i]` after `apps[i]` for the first N apps (no search active); tap -> `openLink`, skip tap ads / recents / return-ad logic.
6. Home folders: when `Game_Folder_Show`, fill folder 1 with games 1-20 and folder 2 with 21-40 and use the Games / More games titles.
7. Keep the analytics event `quiz_game_icon_click` (slot = drawer / folder) if the app has analytics.
8. Remember: the link opens in the external browser, so no in-app WebView, no overlay and no permission is needed.
