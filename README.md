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

One stable 4096-bit key signs every build (`keystore/lightnoise.jks`, committed on
purpose) and exactly one APK ships per release, which is what Obtainium needs. The
certificate SHA-256 is pinned in `signing-fingerprint.txt` and CI fails on drift —
a changed certificate otherwise surfaces only as `Failure: Invalid` at install time.

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
| 7 | Stream | Two noise bands around 620 Hz and 1.65 kHz, plus pitch-drifting gurgles |
| 8 | Fan | Oscillating head sweeping over 7.1 s, mains hum, blade beating |
| 9 | Train | Rail rumble with a rail-joint clack every ~1.5 s |
| 10 | Campfire | Ember bed with woody crackles that arrive in bursts |
| 11 | Cafe | Fourteen quiet talkers of formant-filtered noise, distance-filtered, plus cutlery |
| 12 | Wind | One gust envelope driving amplitude, cutoff and leaf hiss together |

## Using it

Three tabs, with the transport always visible above them.

- **SOUNDS** — tap any sound to start it immediately.
- **MIX** — layer A and layer B with independent levels, plus master volume. Layer B is
  muted while it is set to None.
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
- **Output runs through a look-ahead limiter, and that is what makes the app loud
  enough.** Normalising generators to 0.12 RMS put playback at −24.6 dBFS, about 10 dB
  under music and podcasts, so the phone had to be at full volume to hear anything.
  These sounds have a crest factor near 7 — the bed sits far below the occasional rain
  drop or spoon clink — so raising the master alone clips transients long before the bed
  gets loud. A 1.7x makeup gain plus limiting lands the default at −15.7 dBFS and full
  master at −14.0, level with mastered music. The look-ahead is not decorative: a plain
  feedforward limiter derived its gain from the sample it was already outputting and let
  peaks through at exactly 1.0, and its peak follower needs to *hold* for the length of
  the delay line or the gain starts recovering before the peak it is guarding against
  arrives.
- **`material-icons-extended` is banned** — on LightTip it alone was ~30 MB. `abiFilters`
  is arm64 only.
- **The launcher icon is a vector adaptive icon with no PNG buckets.** minSdk is 29, so
  every device this installs on supports adaptive icons. Five chunky bars rather than
  seven thin ones: an adaptive icon only shows its inner 72 of 108 dp, and a 6 dp bar
  lands at about two pixels in a launcher grid.

## Tests

The synthesis layer has no Android imports, so it runs on the JVM:

```bash
./gradlew :app:testDebugUnitTest
```

The tests are less about correctness than about stability: a state-variable filter that
goes unstable ten minutes into a session is a screech at 3 a.m., and a five-second
listen will not catch it. They render 60–120 s of every sound and assert no non-finite
samples, no clipping, no DC drift, an audible-but-not-loud RMS, and less than 12 dB of
spread across the set.

Three of them check character rather than safety, because "it sounds wrong" is otherwise
only findable by ear:

- The fan's envelope must correlate with itself one 7.1 s sweep later and anti-correlate
  half a sweep later. Measuring how far the level *swings* does not work — brown noise
  wanders about as much over a second as the fan sweeps. The test also runs the same
  measure on pink noise and fails if it stops discriminating.
- Campfire must keep under 2 % of its energy above 2.5 kHz, so crackles stay woody
  instead of drifting back toward static.
- Cafe must concentrate over 45 % of its energy in the 300–3400 Hz speech band and beat
  pink noise there by 1.8x, keep under 20 % of its energy below 250 Hz, and have a
  *smoother* envelope than pink noise. The last two encode a fix: an earlier version
  sounded, accurately, demonic. Sharp formants with F1 dipping into the chest register,
  gated deeply on a few loud talkers, is unpitched noise doing vowels — the ear reads it
  as something speaking that should not be able to.
- The limiter has its own three: it must hold a 0.9 ceiling against 2.5-amplitude spikes,
  leave a quiet bed within 2 % of unity, and release fully within a second.

CI runs the whole suite before it builds the APK.
