# Cesta Launcher

A minimal Android launcher with **home screen folders**.

Cesta Launcher is a fork of [Olauncher](https://github.com/tanujnotes/Olauncher) by
[tanujnotes](https://github.com/tanujnotes). All the credit for the launcher itself goes to
the original author; this fork only adds a few things on top and follows upstream weekly.

> *Cesta* is Spanish for *basket*.

## What is different from Olauncher

- **Folders on the home screen.** Long press a home slot → *Create folder*. The app already in
  that slot becomes the first app of the folder. Long press a folder to add, remove, rename or
  delete it; tap it to open.
- **Home apps survive reboots.** Right after boot, or while the work profile or private space is
  locked, Android may briefly report apps as missing. Olauncher then cleared that home slot for
  good; Cesta only clears it when the app was really uninstalled.
- Its own app id (`io.github.jesusg703.cesta`), so it installs next to Olauncher.
- No rating, sharing or "Pro" prompts, English only.

Everything else (gestures, hidden apps, screen time, daily wallpapers…) is Olauncher.

## Install

Download the APK from [Releases](https://github.com/jesusg703/Olauncher/releases) and open it on
your phone. Check the file against the `.sha256` next to it.

Releases are signed with this certificate (SHA-256):

```
C3:E4:E9:F2:18:A2:D9:3C:44:97:A6:E7:5E:8D:34:CB:94:57:C6:A5:7E:74:80:DE:0A:B2:9B:7F:3C:52:21:E6
```

Verify with `apksigner verify --print-certs cesta-launcher-*.apk`.

## Privacy

Cesta Launcher does not collect, store or share any data. It has no analytics and no ads.

- The optional **daily wallpaper** downloads images from the Olauncher wallpaper list
  (a GitHub gist by the original author) and Unsplash. Nothing is sent about you.
- The optional **double tap to lock** uses the Accessibility Service only to lock the screen.
- Folders and settings stay in the app's private storage on your phone.

## How this fork is maintained

| Branch | Content |
| --- | --- |
| `master` | Exact mirror of upstream Olauncher |
| `carpetas` | Cesta Launcher (default branch) |

- Every Monday a workflow merges upstream into `carpetas`, runs the tests and pushes it.
- Fork changes live in separate files (`data/Folders.kt`, `ui/FolderUi.kt`, `src/cesta/res`, …)
  and an appended block in `app/build.gradle`, so upstream merges stay clean.
- Every push runs unit tests, lint and an emulator test that creates folders, **reboots the
  device** and checks they are still there.
- Fixes that also help Olauncher are proposed upstream.

Build locally with `./gradlew assembleDebug` (JDK 17, Android SDK).

## License

[GPL-3.0](../LICENSE), same as Olauncher. Olauncher © tanujnotes; folder support and other
changes © jesusg703.
