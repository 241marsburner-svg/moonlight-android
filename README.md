# Artemide TV

A game streaming app for the **TCL C7K** (Google TV, MediaTek Pentonic 700 / MT5896), tuned for
the lowest delay and a picture that does not stutter. It streams games and the desktop from a
Windows PC running [Apollo](https://github.com/ClassicOldSong/Apollo) or
[Sunshine](https://github.com/LizardByte/Sunshine).

It is a personal build of [Artemide](https://github.com/derflacco/moonlight-android) by
derflacco, a performance fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android),
which is itself a fork of [Moonlight](https://github.com/moonlight-stream/moonlight-android).
Everything those apps do still works. This build adds what a TV needs on top. It installs next
to other Moonlight-based apps (app ID `com.limelight.artemidetv`). Other Android TVs with
MediaTek chips may work just as well, but it is only tested on the C7K.

## Install

**First time**, on the TV: install the **Downloader** app from the Play Store, allow it to
install apps when it asks, and open this link in it:

    https://github.com/241marsburner-svg/moonlight-android/releases/latest/download/ArtemideTV.apk

**Updates** come from inside the app: **Settings → Artemide TV → Check for updates**. The app
also asks by itself when a new build is out (at most every 6 hours). The first time, the TV
asks you to let Artemide TV install apps. If that page does not open, the switch is under
*TV Settings → Privacy (or Apps) → Security & restrictions → Unknown sources*. Your settings
and the pairing with your PC are kept.

`ArtemideTV.apk` works on any ARM TV. The `arm64-v8a` and `armeabi-v7a` files on the
[releases page](https://github.com/241marsburner-svg/moonlight-android/releases) are smaller
versions of the same app.

## Best settings to start with

Open **Settings → Use the recommended TV settings**. That sets:

| Setting | Value | Why |
|---|---|---|
| Resolution / frame rate | 1440p, 60 FPS | This chip decodes 1440p in about 7 ms against 13 ms for 4K, and the TV scales it to the panel |
| Video codec | HEVC | Proven on this TV; the auto-tune now also tests AV1 |
| Bitrate | 80 Mbps on fast Wi-Fi, 60 or 40 on a slower link | 100 Mbps stuttered over the C7K's Wi-Fi in the auto-tune; its Ethernet port is only 100 Mbit |
| Frame pacing | TV game | Smallest buffer that still hides Wi-Fi bunching |
| Ask the TV for game mode | On | The TV's own picture processing otherwise adds a lot of delay |
| Performance overlay | Off | Anything drawn over the video costs this TV about 25 ms per frame; the stream report after each stream has the same figures |
| Free up TV memory before each stream | On | Ends other apps' background processes (the TV has 2.4 GB of RAM) |
| Stream report after each stream | On | Costs nothing noticeable, shows what went wrong |

Then let the **auto-tune** find what works best on your network (below). Audio: *TV adaptive*
is the default. Choose *Off* if you listen on a headset at the PC.

On the PC (Apollo/Sunshine):
- connect the PC to the router by cable, and the TV over 5 GHz Wi-Fi (or cable)
- on an Intel Arc GPU, set the QuickSync preset to *veryfast* for the lowest encoding delay
- cap the game at the stream's frame rate (60) with its own limiter, so frames come evenly

## What this build adds

**Picture and delay**
- *TV game* and *TV smooth* frame pacing. Each frame is given its own refresh of the TV's
  screen, so frames that arrive in a bunch over Wi-Fi are shown one per refresh instead of being
  dropped. TV game keeps one frame of buffer (up to three while Wi-Fi delivers bigger bunches);
  TV smooth keeps up to three, for video.
- The MediaTek decoder is set up with the low-latency options it really accepts. It also gets
  a bigger input buffer, so a large keyframe after packet loss no longer ends the stream.
- A newer streaming core (moonlight-common-c) with fixes for loss recovery and faster error
  correction, plus patches from [moonlight-tcl](https://github.com/pabragin/moonlight-tcl).
- Raised priorities and Android performance hints for the streaming threads, unbuffered
  controller input, and Wi-Fi that is kept awake between frames.
- The TV is switched to the matching display mode and asked for its game picture mode.
- Audio that keeps a small, self-adjusting cushion, so Wi-Fi hiccups don't crackle.

**On the TV remote**
- Back opens the stream menu (it used to go to the PC).
- The auto-tune starts from the app list: hold OK on an app (e.g. Desktop) → *Auto-tune*.

**Measuring and fixing**
- **Stream report** after each stream: frames shown, hitches, network pauses, loss, round trip,
  decoder and audio figures, and the TV's memory and temperature.
- **Auto-tune**: about 11 short test streams (resolutions, frame rates, bitrates, HEVC against
  AV1, both pacing modes), each scored for *smoothness*, *latency* and *picture*. At the end you
  can use the best one or pick one yourself. *Details* shows how each score came about.
- **Latency test**: button-to-screen time, with `latency-test.html` open on the PC.
- **Upload log**: puts everything measured into one log, removes IP and MAC addresses,
  keys and IDs, uploads it to a paste site, and shows a link and QR code you can send on.
  The log includes the settings each stream really used.

## Auto-tune

1. On the PC, open `stream-test.html` (from the release page) full screen in a browser, or
   start a game. Every test should see the same moving picture.
2. On the TV, hold OK on that app in the app list and choose *Auto-tune*, or press Back
   during a stream and choose *Auto-tune*.
3. Don't touch the controller for about 11 minutes. To stop early, press Back on the auto-tune
   screen between two tests, or quit the stream.

The scores (0 to 100, higher is better):
- **Smoothness**: frames shown against what the screen can show. Points come off for the time
  the picture stood still, for lost frames, and for long network pauses. 0 means the deductions
  added up to more than 100; *Details* shows which ones.
- **Latency**: what the TV adds (decode, buffer, compositor), plus half the network round trip
  and half a frame interval.
- **Picture**: resolution and bits per pixel. AV1 counts as 30 % more efficient than HEVC.

The total is 40 % smoothness, 35 % latency and 25 % picture. A test below 70 smoothness is
never chosen as best while a smoother one exists. The full log goes to
`Download/ArtemideTV` on the TV.

## Tools for the PC

Both files are on every [release](https://github.com/241marsburner-svg/moonlight-android/releases):
- `stream-test.html`: a steady moving picture for the auto-tune. Press F for full screen,
  L for a lighter version.
- `latency-test.html`: for the latency test. Open it full screen in the stream, turn on
  *Settings → Latency test*, and press A, B, X or Y about 20 times.

## Building

GitHub Actions builds every push to `tv-c7k` and publishes it as release `tv-build-N`. That
is the link above, and what the in-app updater looks for. Locally:

    git submodule update --init --recursive
    bash tv-patches/apply.sh        # patches for moonlight-common-c
    ./gradlew assembleNonRoot_gameTv -ParmOnly -PskipLintVital -PciVersionCode=579999

You need the Android SDK (platform 36) and NDK 27.0.12077973. The APKs land in
`app/build/outputs/apk/nonRoot_game/tv/`. CI builds use version code 570000 plus the build
number, so a local build only installs over one with a lower number. The TV build is signed
with the key in `tv-signing/` (see the README there), so every build installs as an update
over the previous one.

Branches:
- `tv-c7k`: this TV build (the default branch)
- `moonlight-noir`: Artemide's main branch as it was when this fork was made (the TV build is
  based on a newer Artemide version)
- `ci-logs`: build logs when a CI build fails

## Credits and license

- Moonlight: [Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg),
  [Aaron Neyer](https://github.com/Aaronneyer), [Andrew Hennessy](https://github.com/yetanothername)
  and contributors
- Artemis: [ClassicOldSong](https://github.com/ClassicOldSong) and contributors
- Artemide: [derflacco](https://github.com/derflacco)
- TCL/MediaTek findings and patches: [pabragin/moonlight-tcl](https://github.com/pabragin/moonlight-tcl)

Licensed under the GPL v3 (see `LICENSE.txt`). Third-party parts keep their own licenses; see
`THIRD_PARTY_NOTICES.md`.
