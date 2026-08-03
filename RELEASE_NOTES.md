## LightNoise v1.1 — Shake to report, and a quieter night

**Two changes: the app can file its own bug reports now, and it stops waking the phone up all
night for a notification that never changes.**

### The notification was being rewritten 1,400 times a night

The playback notification carries a countdown when you set a sleep timer, so it had a loop
refreshing it every 20 seconds. That loop ran whether or not a timer was set — and without one
the subtext is the fixed string "Playing". So an ordinary overnight session posted the same
identical notification about 1,400 times before morning, waking the process out of Doze each
time, while the playback wakelock was held and the CPU could not idle between them.

It is event-driven now. The controller already ticks the countdown once a second, and only while
a timer job exists, so the service just watches what it publishes and re-posts when the line that
shows would actually change. With no timer, that is once. The audio is untouched — this was
always the notification, never the sound.

### Shake the phone to report a bug

Shake twice — there and back, twice — and a sheet comes up. Pick what happened from five chips
and add a note in your own words if you have something to add. The note is optional but it is the
part that carries anything, and what you type becomes the title of the issue. The report brings
the screen you were on, app and firmware versions, free space, heap, and the stack trace if the
app died the last time you had it open.

Reports queue on disk before anything is sent. If there is no network they wait on the phone.

The gesture counts reversals rather than force — setting a phone down hard clears any threshold a
shake clears, but only a shake *reverses* — so walking with the app open never fires it. The
accelerometer only runs while you are looking at the app, which for this app is rarely.
