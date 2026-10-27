![mosu](.github/assets/banner.png)

# mosu

[![Android](https://github.com/Artyom151/mosu/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/Artyom151/mosu/actions/workflows/android.yml)

**mosu** (mobile osu!) is a free circle clicking rhythm game for Android. It's a fork of
[osu!droid](https://github.com/osudroid/osu-droid) with a redesigned interface, local multiplayer and quality of life
fixes.

## Features

- **Material 3 Expressive design.** Settings, downloads and new screens are built with Jetpack Compose and follow your
  wallpaper colors (Material You) on Android 12 and newer.
- **LAN multiplayer.** Host a room on your phone and play with friends on the same Wi-Fi or on your hotspot, no account
  needed. Rooms are found automatically, or joined by IP address. Players missing the beatmap can download it from the
  host in one tap.
- **Skin downloader.** Browse skins with previews and install them without leaving the game.
- **Beatmap downloads** with live progress, speed and time left.
- **Fixes**, including spinners missing with osu!stable skins and memory issues that got the game closed on recent
  devices.

## Download

Download the APK from the [nightly release](https://github.com/Artyom151/mosu/releases/tag/nightly). It's rebuilt by
[GitHub Actions](https://github.com/Artyom151/mosu/actions/workflows/android.yml) on every push to `master`.

mosu is installed next to osu!droid (package `app.mosu`) and uses the same `osu!droid` folder, so your beatmaps,
skins and scores are shared between both.

## Building

Requirements: Java 17, the Android SDK and NDK 22.1.7171670.

```sh
git clone https://github.com/Artyom151/mosu.git
cd mosu
ANDROID_HOME=~/Android/Sdk ./gradlew assembleRelease
```

The APK is written to `build/outputs/apk/release/`. You can also open the project in Android Studio.

### Signing

Without configuration, release builds are signed with the public debug key of the repository, which is fine for
testing but not for publishing. Create your own key once:

```sh
./gradlew generateKeystore
```

This creates `mosu-release.jks` and stores its credentials in `local.properties` (both are ignored by git). Keep a
backup of the keystore: updates must be signed with the same key. To check the signature of a build:

```sh
./gradlew verifyReleaseSignature
```

### Tests

```sh
./gradlew testReleaseUnitTest
```

## Contributing

Bug reports and pull requests are welcome in [issues](https://github.com/Artyom151/mosu/issues).

## Credits

mosu is based on [osu!droid](https://github.com/osudroid/osu-droid), made by the osu!droid team and its
contributors. osu! is © peppy. Skins are provided by [osuskins.net](https://osuskins.net).

## License

mosu is licensed under the [Apache-2.0](https://opensource.org/licenses/Apache-2.0) License, like osu!droid. See
[LICENSE.md](LICENSE.md) for more information.
