# Block Reels

Android app that helps reduce Reels and Shorts distractions without modifying Instagram or YouTube.

- Block the Instagram Reels button or swiping to the next Reel.
- Allow or block Reels opened from DMs.
- Block the YouTube Shorts button or swiping to the next Short.
- Toggle each option separately. Accessibility permission is required.

**APK:** `android/app/build/outputs/apk/release/app-release.apk` (built APK is local and not included in Git).

## Run

Requires Node.js 22.11+, JDK 17, and the Android SDK. Set `JAVA_HOME` and `ANDROID_HOME`, and add SDK `platform-tools` to `PATH`.

```sh
npm ci
npm start
```

Connect an Android phone, enable USB debugging, and accept the computer's authorization prompt. In another terminal:

```sh
npm run android -- --no-packager --active-arch-only
```

In Block Reels, select **Enable protection**, accept the disclosure, and enable the accessibility service in Android Settings. Keep Metro running and USB connected for debug builds.

## Share An APK

From the project root on Windows:

```powershell
.\android\gradlew.bat -p android :app:assembleRelease
```

Share `android/app/build/outputs/apk/release/app-release.apk`. This standalone build includes JavaScript and needs neither Metro nor USB debugging. Recipients install the APK, allow installation from that source, and enable Block Reels in Accessibility settings. Android may require **App info > menu > Allow restricted settings** for a sideloaded accessibility service.

For each new version, increment `versionCode` and update `versionName` in `android/app/build.gradle`, then run the build command above. Share the newly generated APK at the same path. Keep using the same signing key so Android can install it as an update.

The current APK uses the template debug signing key: suitable for private testing, not a public production release. Use a private release key before publishing, keep it backed up outside Git, and use the same key for future updates. APKs, local environments, build outputs, and signing secrets are ignored; commit the source and dependency lockfiles.

## Check

```sh
npm run typecheck
npm run lint
npm test -- --runInBand
```

From `android`, run `./gradlew :app:testDebugUnitTest` (`.\gradlew.bat` on Windows).

On the phone, test each switch separately: blocked tabs, a Reel opened from a DM, video swipes, likes, comments, sharing, and saving. Close comments and immediately swipe upward to check transition protection. Confirm normal messages, posts, Stories, long-form videos, and Back still work. Opening a share panel is enough to test access; sending a message is not required.

## Layout

- `App.tsx`: settings and permission disclosure.
- `src/blocker.ts`: native bridge types and setting labels.
- `android/app/src/main/java/com/blockreels`: native module, service, and blocking rules.
- `__tests__` and `android/app/src/test`: UI and native rule tests.

## Release History

Each build replaces the local APK at `android/app/build/outputs/apk/release/app-release.apk`; older APKs are not archived in this repository. Entries describe implementation changes, not guaranteed compatibility with every Instagram/YouTube version.

- **1.4.2 (code 8, current):** Delay fallback tap injection by 50 ms to allow the touch shield's pass-through flag to take effect. Preserve click-first button handling and existing scrolling behavior. Single-tap playback behavior requires device verification.
- **1.4.1-rollback (code 7):** Restore v1.4 interaction behavior after the v1.5 regression. Restore click-first tap handling and the 100 ms panel scroll interval; remove the per-drag target cache. The higher version code permits an in-place rollback without clearing settings.
- **1.5 (code 6, withdrawn):** Coordinate-only taps and cached 32 ms panel scroll updates caused reported interaction failures. Reverted; do not distribute this version.
- **1.4 (code 5):** Add a horizontal-only replay fallback for Instagram's non-scrollable picture carousel. Update panel scrolling during dragging and request proportional scroll amounts on supported Android versions.
- **1.3 (code 4):** Fit the touch shield around the visible keyboard so Likes search and comment inputs can receive keyboard touches.
- **1.2 (code 3):** Find Comments, Likes, and Share lists through their panel ancestors; support both swipe axes in Share while excluding the underlying Reel pager.
- **1.1 (code 2):** Add accessibility-action support for carousel swipes, timeline seeking, and recognized Likes lists.
- **1.0 (code 1):** Initial Android app with five independent switches, accessibility consent, local settings, and standalone APK export. Pre-versioned patches refined Stories exclusions, DM Reel detection, tap forwarding, and comment/share transition protection.

## Limitations

Detection depends on the installed Instagram/YouTube version and exposed accessibility labels. Scroll protection filters video gestures and forwards taps. Horizontal swipes use recognized carousel controls or explicit left/right scroll actions; the Instagram `clips_carousel_viewpager` also supports a fixed-Y simulated swipe fallback. Timeline seeking requires an exposed progress action. The shield stays active over viewer panels; scrollable comments, likes, and sharing lists are targeted through their panel ancestors. Share lists support both vertical and horizontal scrolling; the underlying Reel pager is excluded. Panel scrolling updates during dragging, with proportional scroll amounts on Android 15+ when supported by the target app. Older versions or unsupported lists may still move in page-sized steps. Controls without exposed scroll actions may remain blocked. Seeking applies when the drag ends. Drag-to-dismiss, long presses, and double taps are not guaranteed; use the panel's close control or Back. Simulated taps and carousel swipes temporarily release the touch shield and are not a tamper-proof lock.

All detection runs locally. The service does not capture screenshots or store messages. Accessibility access can expose visible interface content; disable it in Android Settings at any time.

The current release build configuration uses the template's debug signing key. Configure a private release key before publishing or distributing a production APK.