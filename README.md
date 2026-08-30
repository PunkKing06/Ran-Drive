# Random Drive

An Android app for drivers who just want to go — pick a random direction
within a radius you choose, and get real, in-app turn-by-turn guidance that
keeps rerouting itself somewhere new, forever, instead of taking you to one
fixed point and stopping.

## What it does
1. Finds your current location on a live embedded Google Map.
2. Slide to set how far away each random leg can be (1–50 km).
3. Tap **"Pick a Random Direction"** — it picks a random bearing and distance
   within that radius and drops a preview pin.
4. Tap **"Start Random Drive"** — it fetches a real, road-following route to
   that pin and shows your **next up to 3 turns** in a card over the map,
   spoken aloud as you approach each one.
5. As you drive, it keeps itself going: once you're down to your last few
   turns, it quietly queues up a fresh random continuation so there's always
   something ahead of you. If you miss a turn (or peel off on purpose), it
   notices you've left the route and rolls a brand-new random direction from
   wherever you ended up — same thing on arrival. You never actually "reach"
   a final destination; it just keeps wandering.
6. Tap **"🚻 Nearest Toilet"** any time — finds and navigates to the closest
   public restroom (this one still hands off to Google Maps — see below).
7. **"⏹ Stop Drive"** ends the session.

### In-app turn-by-turn (no hand-off to Google Maps)
Earlier versions of this app just launched Google Maps' own navigation to a
single random point — accurate, but it meant one "best route" to one fixed
destination, not a genuinely random drive. This version instead fetches real
road-following routes itself, from **OSRM** (Open Source Routing Machine)'s
free public routing engine — built on OpenStreetMap data, no API key, no
billing. It parses the turn-by-turn steps OSRM returns (maneuver type,
street name, location) into plain-language instructions, draws the route on
the embedded map, and shows the next 3 upcoming turns in a card.

While a drive is active, the app tracks your live GPS position and:
- **Speaks each upcoming turn** via Android's built-in text-to-speech, with
  an early "in 150 meters, turn left onto X" warning plus the turn itself
  as you reach it — so you're not stuck reading the phone screen while
  driving.
- **Queues a random continuation** once you're down to your final few
  upcoming turns, so the route never actually runs out.
- **Detects a missed or deliberate turn** by checking your live position
  against the real route polyline (not just straight-line distance to a
  destination, like an earlier version of this app did) — if you're more
  than ~60m off the road you were supposed to be on, it fetches a fresh
  random route from right where you are.
- **Treats arrival the same as a missed turn** — reaching the destination
  just triggers picking a new random direction, keeping the drive going.

**Honest limitations:**
- **Foreground only.** Unlike the old version (which handed off to Google
  Maps and ran a background service), this tracks your position from inside
  the app's own screen. If you lock your phone or switch apps, tracking
  pauses. Keep the app open and the screen on while driving (a dash mount
  helps).
- **No live traffic, lane guidance, or speed limits** — those are things
  Google's own navigation stack does that a hobby project reasonably can't
  replicate. This is turn-by-turn direction, not a full nav replacement.
- **OSRM's public demo server** (`router.project-osrm.org`) is meant for
  light/personal use, not heavy production traffic. Fine for one driver's
  app; if it ever feels slow or goes down, the fix is self-hosting OSRM or
  pointing `OsrmClient.kt` at a different OSRM-compatible instance.
- **Route-deviation detection checks distance to the whole route polyline**,
  not just the remaining unfinished portion — on a route that loops back
  near itself, this could rarely under-trigger. Not a big deal for a casual
  drive, just worth knowing.
- Tuning constants live at the top of `MainActivity.kt`: `DEVIATION_METERS`
  (default 60m), `ARRIVAL_METERS` (default 30m), `WARNING_METERS` (default
  150m — how far out the spoken early warning fires), and
  `LOW_STEPS_THRESHOLD` (default 3 — how soon it queues a continuation).

### Nearest toilet
Google's Places data doesn't actually have a filterable "public restroom"
category — restroom is just a yes/no attribute attached to other venues, not
its own place type. So this feature queries **OpenStreetMap's free Overpass
API** instead, which has real, purpose-tagged public toilet locations
worldwide, and needs no API key or billing. It searches a 3 km radius first,
then 10 km if nothing turns up, picks the closest result, and launches
driving navigation straight to it. If OSM has nothing mapped nearby, it
falls back to opening a plain "public restroom" search in Google Maps so
you're never left with a dead button.

Two things worth knowing:
- Coverage depends on how well your area is mapped in OpenStreetMap — dense
  in most cities, sparser in rural areas.
- The public `overpass-api.de` endpoint is rate-limited for heavy/commercial
  use. For occasional personal use this is fine; if you want to scale this
  up, look at self-hosting Overpass or an alternative mirror.

## Setup (required before it will run)

1. **Open in Android Studio.** File → Open → select the `RandomDrive` folder.
   Let Gradle sync (it will download dependencies automatically).

2. **Get a Google Maps API key** — see "Getting a Google Maps API key"
   below for a full walkthrough. Free for this app's usage (Maps SDK for
   Android has unlimited free mobile usage), though Google still requires
   billing to be enabled on the project to issue the key at all.

3. **Add the key** in `app/src/main/AndroidManifest.xml`, replacing:
   ```
   android:value="YOUR_GOOGLE_MAPS_API_KEY_HERE"
   ```
   with your real key.

4. **Run it** on a device or emulator with Google Play services and the
   Google Maps app installed (an emulator with a "Google APIs" or
   "Google Play" system image works; a plain AOSP image won't have Maps).

## Getting a Google Maps API key

1. Go to https://console.cloud.google.com, sign in, and create a new
   project (top project dropdown → New Project).
2. **APIs & Services → Library** → search "Maps SDK for Android" → **Enable**.
   That's the only API this project needs — don't enable Places, Directions,
   etc., since those aren't free and nothing here calls them.
3. **Billing** → link a card. Required by Google to issue any key, even
   though your actual usage here will be free.
4. **APIs & Services → Credentials** → **+ Create Credentials → API key**.
   Copy the key that appears.
5. Click into the new key to restrict it:
   - **Application restrictions → Android apps → Add an item:**
     - Package name: `com.example.randomdrive`
     - SHA-1 certificate fingerprint: **`B3:79:E7:4E:CB:E6:16:3A:FB:0C:87:38:F8:E9:A7:26:2B:CD:C7:44`**

       This is the fingerprint of the `app/debug.keystore` file already
       committed in this project (see "About the debug keystore" below) —
       you don't need Android Studio, `keytool`, or any local tooling to
       get it; it's the same for every clone of this repo and every CI
       build.
   - **API restrictions → Restrict key →** check only "Maps SDK for Android."
   - Save.
6. Paste the key into the manifest (step 3 above), or into the
   `MAPS_API_KEY` GitHub secret if you're using the Actions build below.

### About the debug keystore
Android signs every debug build with a "debug keystore" — normally your
machine (or CI runner) auto-generates one the first time you build, with a
random signing key. That's a problem for a key restricted to a specific
SHA-1: your laptop, your desktop, and every fresh GitHub Actions run would
each get a *different* random fingerprint, so the restriction would keep
breaking.

To avoid that, this project **commits a fixed `app/debug.keystore`** and
`app/build.gradle` points the `debug` build variant at it explicitly. Every
build — local or CI — signs with the same key, so the SHA-1 above is stable
forever. (Committing a *debug* keystore like this is normal and fine; it's
not sensitive. Never do this with a real release keystore.)

## Getting an APK without installing Android Studio

If you just want an installable `.apk` and don't want to set up Android
Studio, this project includes a GitHub Actions workflow
(`.github/workflows/build-apk.yml`) that builds one for you in the cloud:

1. Create a new (can be private) GitHub repo and push this folder to it.
2. Get a Maps API key — see "Getting a Google Maps API key" above.
3. In the repo, go to **Settings → Secrets and variables → Actions → New
   repository secret**, name it `MAPS_API_KEY`, and paste your key in.
4. Go to the **Actions** tab → **Build APK** workflow → **Run workflow**
   (or just push a commit — it runs automatically).
5. Once it finishes (a couple of minutes), open the run and download the
   **RandomDrive-debug-apk** artifact — it's a zip containing `app-debug.apk`.
6. Copy that APK to your phone and open it. You'll need to allow
   "install unknown apps" for whatever app you use to open it (Files,
   Chrome, etc.) — this is normal for any app installed outside the Play
   Store, since it's a debug build, not something signed for the Store.

This produces a **debug** build — fine for installing on your own phone, but
not signed for Play Store distribution. If you eventually want to publish
it, Android Studio can generate a proper signed release build/bundle.

## Permissions
Just location — that's it. There's no foreground service or notification
permission anymore, since navigation tracking now happens directly in the
app's own screen rather than a background service.

## Notes / things you might want to tweak
- `minSdk 23` (Android 6.0+) — covers the vast majority of active devices.
- The random point is **not checked against water** before routing to it —
  OSRM's routing itself will refuse/fail gracefully if a point is
  unreachable by road (e.g. literally in a lake), which triggers the normal
  "couldn't fetch a route" retry path rather than crashing, but you may
  occasionally see a route that looks like it's heading toward a coastline.
- The nearest-toilet feature still hands off to the real Google Maps app for
  navigation — a single fixed real destination is better served by Google's
  full turn-by-turn voice guidance and live traffic than by this app's
  simpler in-app version, which exists specifically for the open-ended
  "just keep driving randomly" case.
- No app icon is bundled — Android Studio will use a default one. Add your
  own via Image Asset Studio (right-click `res` → New → Image Asset) if you
  want a custom one.
