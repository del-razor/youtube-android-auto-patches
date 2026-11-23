# YouTube Android Auto patches

A Morphe patch bundle that makes the YouTube app show up in Android Auto.
Use it in Morphe Manager next to the official Morphe patches.

> **Status: first version, not yet built or tested on a device.**
> See "What still needs checking" below.

### How to use these patches

Add this repo URL as a patch source in Morphe Manager: https://github.com/del-razor/youtube-android-auto-patches

<a href="https://morphe.software/add-source?github=del-razor/youtube-android-auto-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

Then patch YouTube with the official patches you already use **plus** the "Android Auto" patch from this bundle.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.4](https://github.com/del-razor/youtube-android-auto-patches/releases/tag/v1.0.0-dev.4)**&nbsp;&nbsp;&middot;&nbsp;&nbsp;`dev`&nbsp;&nbsp;&middot;&nbsp;&nbsp;**2 patches** across **1 apps**&nbsp;&nbsp;&middot;&nbsp;&nbsp;[Full details](PATCHES.md)

| # | App | Patches | Version | Package |
|---|---|---|---|---|
| 1 | [**YouTube**](PATCHES.md#youtube-comgoogleandroidyoutube) | 2 | `experimental 21.30.216` | [`com.google.android.youtube`](https://play.google.com/store/apps/details?id=com.google.android.youtube) |
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

A second, optional patch that puts YouTube's real video on the car display.

- Android Auto lists it as **YouTube Video**. It uses the unofficial Android Auto SDK that
  Fermata Auto uses (`extensions/extension/libs/aauto.aar`) to draw a full screen on the car.
- Every `MediaCodec.configure()` and `setOutputSurface()` call in YouTube is routed through
  `CarVideoBridge`. While the car screen is open and video is allowed, YouTube's video decoder
  draws on the car's surface instead of the phone. Audio and YouTube's own player are untouched.
- The screen follows the car's driving status. Its "no video" flag is what the head unit tells
  Android Auto. When the car allows video, it plays. When it does not, the screen shows artwork
  and the title while audio keeps playing. The patch never fakes or ignores this flag.
  A small readout in the top right corner shows the flag the car is sending.
- Protected (DRM) videos stay on the phone.

**Install requirement:** Android Auto only lists screen-drawing apps whose install record says
the Play Store installed them. "Unknown sources" does not cover them. Install the patched APK
with KingInstaller or a similar tool that opens the
system installer with the Play Store as installer, and repeat that for every update. Morphe
Manager's normal install and `adb install -i com.android.vending` are rejected by current Android
Auto ([details](https://github.com/AndreyPavlenko/Fermata/issues/846)).

**Untested risks:**
- `Car.createCar()` from the SDK may not connect from inside a car screen. Then the readout
  shows "car API error" and video stays off.
- YouTube may stop decoding video when its phone screen is off. Then the car screen stays black
  with audio playing. Keeping YouTube open on the phone, or in picture-in-picture, avoids this.
- The SDK may need resources YouTube does not have, or clash with YouTube's own androidx
  classes. Check `adb logcat -s DelrazorAndroidAuto AndroidRuntime` if the screen crashes.

## What it does not do

- **No video from the audio patch.** Android Auto gives media apps only a browse list and a
  now-playing screen. Video needs the separate "Android Auto video" patch.
- **No browsing from the car (yet).** Start a video on the phone, then control it from the car.
  Subscriptions, Watch later and search from the car would be a later version.

## Setup on the phone

1. Patch YouTube with:
   - the official Morphe patches, including **background playback** (otherwise YouTube stops when
     the screen is off), and
   - **Android Auto** from this bundle.
2. **Let Android Auto show a sideloaded app.** Android Auto hides media apps that the Play Store did
   not install. The official "Change installer source" patch does **not** fix this: it only changes
   what YouTube reports about itself, and Android Auto asks Android directly. Use one of these:
   - Android Auto settings → tap **Version** 10 times → ⋮ menu → **Developer settings** →
     turn on **Unknown sources**. *(Easiest.)*
   - or install the patched APK with the Play Store set as installer, for example over ADB:
     `adb install -i com.android.vending patched-youtube.apk`
3. Connect to the car (or the Desktop Head Unit), open the Android Auto app launcher and look for YouTube.
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
