# LightNoise

White noise and background sound for the **Light Phone III**. Twelve endless sounds,
a two-layer mixer, and a sleep timer, in a black-and-white UI that matches LightOS.

Repo name **LightNoise**, launcher label **White Noise**, applicationId
`com.gios.lightnoise`.

## Install

Grab the APK from the [latest release](../../releases/latest) and sideload it:

```bash
adb install -r LightNoise-v1.0.<run>.apk
```

Every push to `main` publishes a signed release, so `-r` upgrades in place.

## Sounds

All twelve are **synthesised in real time**, not looped audio files. Nothing repeats,
there is no seam to notice at 3 a.m., and the APK carries no audio assets.

| | Sound | How it is made |
|---|---|---|
| 1 | White noise | Uniform noise, rolled off above 14 kHz |
| 2 | Pink noise | Kellet economy filter, −3 dB/oct |
| 3 | Brown noise | Leaky integrator, −6 dB/oct |
| 4 | Rain | Hiss + mid body + ~55 discrete drops/sec, slow gusts |
| 5 | Thunderstorm | Heavier rain, plus a swept low rumble every 20–70 s |
| 6 | Ocean waves | 7–13 s swell driving both a lowpass sweep and foam |
| 7 | Stream | Two noise bands plus pitch-drifting resonant gurgles |
| 8 | Fan | Brown bed, mains hum, 23.5 Hz blade beating |
| 9 | Train | Rail rumble with a rail-joint clack every ~1.5 s |
| 10 | Campfire | Ember bed with crackles that arrive in bursts |
| 11 | Cafe | Speech-band noise under five modulators, plus cutlery |
| 12 | Wind | One gust envelope driving amplitude, cutoff and leaf hiss together |

### Your own loops

Copy `.ogg`, `.mp3`, `.m4a`, `.wav`, `.flac` or `.opus` files to **`/sdcard/LightNoise`**
(or `Music/LightNoise`, or `Download/LightNoise`) and they appear under MY LOOPS:

```bash
adb shell mkdir -p /sdcard/LightNoise
adb push my-recording.ogg /sdcard/LightNoise/
```

Files are decoded once to mono and looped from memory with a 50 ms crossfade baked in,
so the wrap is inaudible. Anything over 150 s is truncated.

## Using it

Three tabs, with the transport always visible above them.

- **SOUNDS** — tap any sound to start it immediately.
- **MIX** — layer A and layer B with independent levels, plus master volume. Rain over
  brown noise is the usual pick. Layer B is muted while it is set to None.
- **TIMER** — off / 15 / 30 / 45 / 60 / 90 / 120 min. Volume fades over the last 25
  seconds, then playback and the wake lock are released.

The mix and timer choice are saved, so the app reopens on whatever you fell asleep to.
Playback runs in a foreground service with a partial wake lock and survives the screen
going off; there is a Stop action in the notification shade.

## Design notes

- **Plain sideloaded APK, not a LightOS SDK tool.** The `light-sdk` Gradle plugin's
  dependency allowlist has no audio path of its own and its `BLOCKED_IMPORTS` ban
  `android.app.*`, which rules out a foreground service. Same conclusion as LightPass
  and LightTip.
- **Greyscale-only palette.** Selection *inverts* rather than tints — on a matte
  black-and-white panel that is the only state change legible at arm's length in a dark
  room. Akkurat is pulled out of `SystemFonts` so the app matches LightOS chrome.
- **Levels are segmented blocks, not Material sliders.** A slider thumb is a poor
  target on a 3.92" screen and its track is nearly invisible in greyscale.
- **All twelve sounds are level-matched to 0.12 RMS**, so swapping presets changes the
  texture and not the volume. Layer and master gains are squared before they are
  applied, because a linear amplitude slider feels dead until its last quarter.
- **`material-icons-extended` is banned** — on LightTip it alone was ~30 MB. `abiFilters`
  is arm64 only.

## Tests

The synthesis layer has no Android imports, so it runs on the JVM:

```bash
./gradlew :app:testDebugUnitTest
```

The tests are less about correctness than about stability: a state-variable filter that
goes unstable ten minutes into a session is a screech at 3 a.m., and a five-second
listen will not catch it. They render 60–120 s of every sound and assert no non-finite
samples, no clipping, no DC drift, an audible-but-not-loud RMS, and less than 12 dB of
spread across the set. CI runs them before it builds the APK.
