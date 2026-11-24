# YouTube Android Auto patches

A Morphe patch bundle that makes the YouTube app show up in Android Auto.
Use it in Morphe Manager next to the official Morphe patches.

> **Status: experimental.** Builds come from GitHub Actions. The car entry, the car connection and the
> driving-status flag work on a Pixel 7 Pro with Open Head Unit; opening YouTube on the car display is
> still being tested.

### How to use these patches

Add this repo URL as a patch source in Morphe Manager: https://github.com/del-razor/youtube-android-auto-patches

<a href="https://morphe.software/add-source?github=del-razor/youtube-android-auto-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

This repo is private, and new builds are published from the `dev` branch, so Morphe Manager
needs a token and the dev link:

1. On GitHub, create a fine-grained personal access token with access to only this repository
   and **Contents: Read-only**.
2. In Morphe Manager: Settings → Advanced → **GitHub PAT**, and paste the token.
3. Add the source `https://github.com/del-razor/youtube-android-auto-patches/tree/dev`.
   Manager then picks up each new release from `patches-bundle.json` on its own.

Then patch YouTube with the official patches you already use **plus** "Android Auto video" from this bundle.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.14](https://github.com/del-razor/youtube-android-auto-patches/releases/tag/v1.0.0-dev.14)**&nbsp;&nbsp;&middot;&nbsp;&nbsp;`dev`&nbsp;&nbsp;&middot;&nbsp;&nbsp;**3 patches** across **2 apps**&nbsp;&nbsp;&middot;&nbsp;&nbsp;[Full details](PATCHES.md)

| # | App | Patches | Version | Package |
|---|---|---|---|---|
| 1 | [**GasBuddy**](PATCHES.md#gasbuddy-gbisgbandroid) | 1 | `experimental 702.10.27044` | [`gbis.gbandroid`](https://play.google.com/store/apps/details?id=gbis.gbandroid) |
| 2 | [**YouTube**](PATCHES.md#youtube-comgoogleandroidyoutube) | 2 | `experimental 21.30.216` | [`com.google.android.youtube`](https://play.google.com/store/apps/details?id=com.google.android.youtube) |
<!-- PATCHES_END -->

## What it does

Android Auto lists a media app when the app has a media browser service and declares
`com.google.android.gms.car.application` in its manifest. YouTube Music has both. The regular
YouTube app has neither, so this patch adds them:

1. **Manifest:** adds the car meta-data, a `res/xml` file declaring `<uses name="media"/>`, and an
   exported service answering `android.media.browse.MediaBrowserService`.
2. **Hook:** after every `new android.media.session.MediaSession(...)` in YouTube's code, the new
   session is handed to `AndroidAutoBridge`. Framework class names are never obfuscated, so this needs
   no version-specific fingerprints.
3. **Service (`AndroidAutoMediaService`):** owns its own media session and mirrors YouTube's playing
   session into it. Title, channel, artwork and play state go to the car. Play, pause, skip, seek,
   fast forward, rewind and YouTube's own custom buttons go back to YouTube.
   The car's browse list holds one item, "now playing".
4. **Caller check:** only Android Auto, Google system apps and the phone's system UI may connect.

## Android Auto video (experimental)

Runs the real YouTube app on the car screen, so the phone is not needed at all.

- Android Auto lists it as **YouTube**. It works without the audio patch.
- YouTube's own built-in Android Auto app (Premium audio only) and its media browser are hidden,
  so this is the only YouTube entry in the car launcher.
- It uses the unofficial Android Auto SDK that Fermata Auto uses
  (`extensions/extension/libs/aauto.aar`) to get a full screen on the car display.
- That screen creates a private virtual display, the size of the car screen, and opens
  YouTube's own main activity on it. YouTube treats it as a normal visible screen, so home,
  search, the player and its controls all work there, and video keeps playing.
- Car touches are passed to that YouTube window in-process. Back, D-pad and rotary keys go to
  YouTube too. Steering wheel media keys control playback.
- The screen follows the car's driving status. Its "no video" flag is what the head unit tells
  Android Auto. When the car allows video, YouTube shows. When it does not, the screen is
  covered and audio keeps playing. The patch never fakes or ignores this flag.
- A readout in the top right corner shows the flag and the car display state. It hides itself
  after a few seconds once everything works.

**Shizuku makes it a real car screen.** With [Shizuku](https://shizuku.rikka.app/) running, the car
screen starts a small helper as the phone's shell user (the same identity scrcpy uses). The helper
creates a trusted car display that:

- keeps YouTube playing with the phone screen off or locked,
- takes the car's touches as real input, so pop-up menus and settings sheets work,
- shows the phone's keyboard on the car when you tap the search box.

Without Shizuku (or root), Android refuses to open YouTube on the car at all.

1. Install Shizuku and start it (on an unrooted phone: Shizuku → Start via Wireless debugging).
   It has to be started again after each phone restart.
2. Open YouTube on the car. The first time, Shizuku asks on the phone whether YouTube may use it.
   Allow it once.

**Back button.** The ◀ button in the bottom left corner is YouTube's back button. A head unit's
own back button may be handled by Android Auto itself, which leaves the app.

**Audio delay.** The car shows the picture a little later than it plays the sound. Tap **A/V** in
the bottom left corner of the car screen and use − and + (25 ms steps) until lips match. The value
is kept. It only applies while the car screen is open.

**Install requirement:** Android Auto only lists screen-drawing apps whose install record says
the Play Store installed them. "Unknown sources" does not cover them. Install the patched APK
with KingInstaller or a similar tool that opens the
system installer with the Play Store as installer, and repeat that for every update. Morphe
Manager's normal install and `adb install -i com.android.vending` are rejected by current Android
Auto ([details](https://github.com/AndreyPavlenko/Fermata/issues/846)).

**Untested risks:**
- Without Shizuku or root, Android refuses to open YouTube on the car display. The readout says
  what to do.
- Typing in YouTube's search box may bring up the keyboard on the phone instead of the car.
  YouTube's voice search should work from the car.
- Pop-up menus may not react to touches from the car yet.
- The SDK may need resources YouTube does not have, or clash with YouTube's own androidx
  classes. Check `adb logcat -s DelrazorAndroidAuto AndroidRuntime` if the screen crashes.

## What it does not do

- **No video from the audio patch.** Android Auto gives media apps only a browse list and a
  now-playing screen. Video needs the separate "Android Auto video" patch.
- **The audio patch has no browsing.** It only mirrors what is playing on the phone. The video
  patch is the one that gives the full YouTube app, with home, search and subscriptions, on the car.

## Setup on the phone

1. Patch YouTube with:
   - the official Morphe patches, including **background playback** (otherwise YouTube stops when
     the screen is off), and
   - **Android Auto video** from this bundle.
2. Install the patched APK with KingInstaller (see the install requirement above). The steps below
   only apply to the optional audio patch.
3. **Let Android Auto show a sideloaded audio app.** Android Auto hides media apps that the Play Store did
   not install. The official "Change installer source" patch does **not** fix this: it only changes
   what YouTube reports about itself, and Android Auto asks Android directly. Use one of these:
   - Android Auto settings → tap **Version** 10 times → ⋮ menu → **Developer settings** →
     turn on **Unknown sources**. *(Easiest.)*
   - or install the patched APK with the Play Store set as installer, for example over ADB:
     `adb install -i com.android.vending patched-youtube.apk`
4. Connect to the car (or the Desktop Head Unit), open the Android Auto app launcher and look for YouTube.
   If it is missing, open the launcher's "Customize" list on the phone and enable it.

## What still needs checking

These are untested assumptions. Each is marked `GUESS` or `UNTESTED` in the code.

- The patcher compiles a new `res/xml` file written with `resourceFile(path, false)` and resolves
  the `@xml/...` reference to it in the manifest.
- YouTube creates its media session through `new MediaSession(...)` in its own DEX
  (it uses an obfuscated copy of androidx MediaSessionCompat, which does).
  If it does not, patching fails with "Could not find where YouTube creates its media session".
- Android Auto is signed with the same Google key as Play services (used by the caller check on
  phones where Android Auto is not a system app). If the car shows YouTube but it never loads,
  check `adb logcat -s DelrazorAndroidAuto` for "Refused media browser connection".
- Morphe Manager can apply patches from two bundles to one app in a single run.

## GasBuddy in Android Auto (experimental)

The "Android Auto gas stations" patch adds a native Android Auto car app to GasBuddy. It uses the
official Android for Cars App Library ("point of interest" category), so it looks and behaves like
other Android Auto apps and works while driving. It needs no Shizuku.

- A map with nearby stations and a list sorted by price ("Cheapest") or distance ("Closest").
  While driving, the car decides how many rows it shows.
- A fuel grade picker (Regular, Midgrade, Premium, Diesel), remembered between drives.
- Tap a station to see its address and all posted prices, then **Navigate** to hand it to
  Google Maps or Waze.
- It uses GasBuddy's own location permission. Open GasBuddy on the phone once and allow location.

### Where the prices come from

GasBuddy is a React Native app, so its station search is JavaScript. The patch watches the requests
it sends through React Native's networking module and remembers its last nearby-station search,
including its sign-in headers. The car screen repeats that search with the car's position and fuel
grade and reads the stations and prices from GasBuddy's reply.

That means:

- **Open GasBuddy on the phone once after installing** and let it show nearby stations. Until it
  has, the car screen asks you to.
- If GasBuddy's sign-in expires while the app hasn't been opened for a while, the car screen says
  so. Opening GasBuddy on the phone refreshes it.

Written against GasBuddy 702.10.27044. Install the patched GasBuddy with KingInstaller, the same as
YouTube: Android Auto ignores Car App Library apps that weren't installed by the Play Store.

**Pixel and other 64-bit-only phones** need the arm64-v8a build of GasBuddy. Some downloads
(for example an APKPure .xapk) only contain the 32-bit armeabi-v7a libraries, which won't install
there.

## 🛠️ Building

This cloud workspace could not build the bundle (Google's and GitHub's package servers are blocked
there). Build it one of these ways:

- **GitHub:** push to a GitHub repo made from this folder. The `release.yml` workflow builds it and
  publishes a release Morphe Manager can use. In repo Settings → Actions → General, enable
  "Allow GitHub Actions to create and approve pull requests".
- **Locally:** set up a GitHub token with `read:packages` as described in the
  [Morphe patcher setup docs](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md),
  then run `./gradlew buildAndroid`. The bundle is `patches/build/libs/patches-*.mpp`; apply it with
  [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).

Development follows the Morphe template conventions: work on the `dev` branch and use semantic
commit messages (`feat:`, `fix:`, `chore:`) so releases are made automatically.

## 📜 License

Licensed under the [GNU General Public License v3.0](LICENSE). Based on the
[Morphe patches template](https://github.com/MorpheApp/morphe-patches-template). See [NOTICE](NOTICE).
