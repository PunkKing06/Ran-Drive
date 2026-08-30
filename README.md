# Random Drive

An Android app for drivers who just want to go — pick a random direction within
a radius you choose, and get turn-by-turn driving directions there via Google Maps.

## What it does
1. Finds your current location on a live embedded Google Map.
2. Slide to set how far away the random destination can be (1–50 km).
3. Tap **"Pick a Random Direction"** — it picks a random bearing and distance
   within that radius and drops a pin.
4. Tap **"Start Driving There"** — it launches Google Maps' normal driving
   navigation to that pin, and starts a background monitor (see below).
5. Tap **"🚻 Nearest Toilet"** any time — finds and navigates to the closest
   public restroom.
6. If you miss a turn (or just feel like going a different way), the app
   notices and quietly picks a fresh random destination from wherever you
   ended up, instead of routing you back to the original pin.

### Auto-reroute on a missed (or intentional) turn
Turn-by-turn navigation itself happens in the real Google Maps app — that's
what gives you full voice guidance, live traffic, and rerouting for free.
The catch is that Google Maps, left alone, will just recalculate a new route
back to the *same* destination if you miss a turn, which isn't very "random
drive." So this app runs a small foreground service (`DriveMonitorService`)
while a drive is active: it watches your real GPS position, and if you
arrive at the destination *or* drift meaningfully away from it, it rolls a
brand-new random point from your current spot and re-launches navigation to
that instead. You'll see a persistent notification ("Random Drive is
active") while this is running, and a **"⏹ Stop Auto-Reroute"** button
appears in the app to turn it off.

**Honest limitation:** since there's no in-app navigation SDK involved, the
service only has straight-line distance to work with — not the actual
road-following route Google Maps is showing you. It infers "you went off
course" from the fact that your straight-line distance to the target grew
past your closest approach so far by more than a threshold, for a couple of
consecutive GPS readings (to filter out normal jitter). On very winding
roads this can occasionally misfire. Two constants at the top of
`DriveMonitorService.kt` control the sensitivity:
- `DEVIATION_THRESHOLD_METERS` (default 400m) — how far past your best
  approach counts as "drifting away." Raise it if it reroutes too eagerly.
- `ARRIVAL_THRESHOLD_METERS` (default 60m) — how close counts as "arrived."

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
Beyond location, the app now also requests:
- **Notifications** (Android 13+) — for the "Random Drive is active" status
  notification while auto-reroute monitoring is running.
- **Foreground service / foreground service location** — manifest-declared,
  no runtime prompt needed; lets the monitor keep working while Google Maps
  is the app on screen.

## Notes / things you might want to tweak
- `minSdk 23` (Android 6.0+) — covers the vast majority of active devices.
- The random point is **not checked against roads or water** — if you're near
  a coastline or a lake, you might occasionally get a destination in the
  water. A cheap improvement: call the Directions API (or the Roads API) to
  snap the random point to the nearest road before showing it. That needs a
  billing-enabled key and a network call, so it's left out of this minimal
  version to keep setup simple.
- Navigation is handed off to the real Google Maps app via an intent
  (`google.navigation:q=...`) rather than drawing the route in-app, so you get
  full turn-by-turn voice guidance, traffic, and rerouting for free.
- No app icon is bundled — Android Studio will use a default one. Add your
  own via Image Asset Studio (right-click `res` → New → Image Asset) if you
  want a custom one.
