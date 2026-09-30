# Falling back from Google Play to open distribution

What to do if Google Play declines the app, or the Play route is dropped, and OpenTimelapse
is published as free and open source software instead. "foss" is the build flavor for that:
the full app, with the donation button, the repository links and all-files access.

Nothing in the code has to be reverted. The changes made for Play (the accessibility
disclosure dialog, target API 36, the 16 KB ffmpeg) are harmless or useful everywhere, and
the Play-only restrictions live in the `play` flavor, which is simply not built.

## Checklist

- [ ] **Repository public.** The About card links to it, the licence screen points at
      `tools/build-ffmpeg.sh` in it, and that script is what satisfies the GPL for the
      bundled ffmpeg. If it was made private for Play, make it public again first, and
      check that no keystore or `keystore.properties` ever reached the history.
- [ ] **Donation page exists.** The button opens `DONATE_URL` in `app/build.gradle.kts`
      (GitHub Sponsors today). Set up the page, swap in a Ko-fi or Liberapay URL, or blank
      it to hide the button - a dead link is worse than none.
- [ ] **Bump** `versionCode` and `versionName`, update the changelog.
- [ ] **Build and verify** as in the README: `./gradlew :core:test :app:assembleFossRelease`,
      then `apksigner verify` on an APK from `app/build/outputs/apk/foss/release/`.
- [ ] **Tag** the release (`vX.Y.Z`) and push the tag.
- [ ] **Publish** the three `app-foss-*-release.apk` files (next section).
- [ ] **Unpublish from Play** if it ever went live there, and see "If the app was on Play".

## Where to publish

**GitHub Releases** - the baseline, and what the other channels feed from. Create the
release for the tag in the browser and attach the three APKs. Users of Obtainium get
updates straight from there.

**IzzyOnDroid** - an F-Droid-compatible repository that takes the APKs from GitHub
Releases as they are, so the prebuilt ffmpeg is not a problem. Request inclusion through
their issue tracker; they check for trackers and proprietary libraries (there are none) and
have a per-APK size limit, so offer the per-ABI APKs rather than the universal one.

**F-Droid main repository** - builds from source on their servers, which is the obstacle:
`libffmpeg.so` is git-ignored and built by hand in WSL. To get in, the F-Droid build recipe
has to run `tools/build-ffmpeg.sh` itself, which means the script must fetch its own
pinned ffmpeg and x264 sources and an NDK must be declared in the recipe. Worth doing only
after the app has settled on IzzyOnDroid. Donation links go in the F-Droid metadata as well
as in the app.

## What users need to be told

Put these in the release notes and the README, because they are the first things a
sideloading user hits:

- **Restricted settings.** On Android 13 and later, an APK installed from a browser or file
  manager cannot be given accessibility access until the user opens the app's App info
  page and chooses "Allow restricted settings" from the menu. Installs through a store
  client such as F-Droid or Obtainium are not affected.
- **Play Protect** may warn about an unknown app that uses accessibility. It is a warning,
  not a block.
- **Developer verification.** Android is phasing in a requirement that sideloaded apps on
  certified devices come from a verified developer, starting in some countries in 2026.
  Check the current state before relying on sideloading alone; registering the package
  name and signing key with Google may be needed even without publishing on Play.

## If the app was on Play

- **Signing key.** Play re-signs uploads with its own key. A copy installed from Play
  cannot be updated by a foss APK signed with `opentimelapse.jks`; those users have to
  uninstall first and lose their config and session history. Say so in the release notes.
- **Paid users.** If the app was sold, anyone who paid now sees it free elsewhere. Decide
  up front whether that needs a note or a refund window.
- **Same code, two names.** If a Play version is ever kept alive next to the foss one, give
  one of them an `applicationIdSuffix` so they can be installed side by side and are not
  mistaken for each other.

## What stays different between the flavors

See the table in [play-submission.md](play-submission.md). In short, foss keeps the donation
button, the source and issue links, optional all-files access for renaming frames, and
`USE_EXACT_ALARM`.
