# Meridian 1.2

A private, offline-first fitness tracker for Android. No accounts, no feed, and your activities never leave your phone.

## Features

**Recording**
- Runs, rides and walks with GPS that keeps going with the screen off, a live notification, a 3-2-1 countdown, pause, hold-to-finish and a pocket lock
- Clean distance: Kalman smoothing, spike rejection and stationary-drift filtering
- Live distance, time, pace (or speed), climb, average pace and moving time; barometer elevation when available
- **Follow a route** you drew, with a dashed line on the map, distance to go and an off-route alert
- **Race your ghost**: your previous effort on the same route, shown as a dot on the map and "+12 s ahead"
- **Today's plan target** as a progress bar, with a buzz when you reach it
- Crash-safe: a snapshot every 15 seconds, offered back if Android kills the app

**Heart rate, watches & steps** (new in 1.1)
- **Live heart rate over Bluetooth** from chest straps (Polar, Garmin HRM, Wahoo…) and watches that broadcast
  heart rate (Garmin "Broadcast Heart Rate", Coros, Polar, Suunto, Amazfit, some Huawei). Pair once in
  Settings › Watches & sensors; Meridian reconnects by itself when you record
- **Fitbit, Garmin, Samsung, Pixel Watch and more via Health Connect**: watch workouts appear in Meridian with
  heart rate, steps and the map (when the watch app shares the route). Phone recordings get your watch's heart
  rate added automatically. Read-only and on-device
- **Step counter** during runs and walks: steps, cadence and stride on the Activity Complete screen, live steps
  and steps-per-minute while recording
- Heart-rate chart, **time in zones** (from your max heart rate), and **Relative Effort from heart rate**
- Heart rate is kept in GPX exports and read from GPX, TCX and FIT imports

**Photos, videos & gear** (new in 1.2)
- **Add up to 5 photos or videos** to any activity, from the camera or your gallery, right on the Activity Complete screen
- **Share with your photo**: pick one of the activity's photos as the background of the share image
- **Gear**: add your shoes, bike or anything else, tick it on Activity Complete, and see total distance and distance left.
  Shoes default to a 700 km / 450 mi life (editable) and you get a warning near the end and a **retire** prompt when worn out.
  Manage everything in Settings › Gear

**Activities**
- History with filters, a weekly chart and month groups
- Route map, splits, pace and elevation charts you can scrub, best pace and highest point
- **Relative Effort** for every activity
- **Matched routes**: "2nd fastest of 6 on this route" with your times on that route
- New personal-best and **badge** cards the moment you finish
- Rename, delete, export GPX, share

**Plan** (training plans)
- Pick the event, its date and distance (3K to marathon, or any distance)
- **Run, Walk or Run-walk**: run a little and walk the rest, with the run bits growing as you get fitter
- **Distance or time targets**, chosen per plan
- Base, build, a lighter week every 4th week, and a taper so you arrive fresh; rest days built in
- A morning reminder and an optional 7 PM nudge if today's session isn't done
- Sessions tick off automatically from your activities; a missed long session doesn't make the next one jump
- Warns you when the dates are short for the distance

**Progress**
- Week / Month / Year totals and charts, comparison with the previous period
- **Goals**: weekly, monthly or yearly distance, time or activity count, with progress rings
- **Fitness & Freshness**: fitness, fatigue and form over the last 90 days
- **Weekly effort** with a suggested range from your last 3 weeks
- **Race predictor** for 5K, 10K, half and marathon (run or walk) from your recent best efforts
- **Personal bests**, streaks, **badges** (30 to earn) and your **personal heatmap**
- **Year in Review** and **Month in Review** stories, shareable to Instagram

**Maps**
- Dark map (OpenStreetMap via MapLibre, no API key), cached for offline use, plus **offline area downloads**
- **Route builder**: tap to draw, loop back or out-and-back, then save and follow

**Sharing**
- Transparent PNG stickers (Stats, Route, Minimal) and a solid Card, in white, yellow or black ink
- Save to Photos, open Instagram (add it with the Photo sticker), or share anywhere

**Your data**
- Import your **Strava archive ZIP** (GPX / TCX / FIT, names and sports kept), or single files
- Export GPX (one activity or all), backups and restore, **automatic backup** to a file you pick (Google Drive works), and Android's Google backup
- Kilometres or miles, GPS accuracy, keep screen on, countdown, background-recording check

## Build and install

1. Install the latest **Android Studio**.
2. Unzip and open the `Meridian` folder (**File › Open**). Let Gradle sync finish.
3. Plug in your phone with **USB debugging** on, then press **Run ▶**.

### Making an APK to share with friends

**Build › Build App Bundle(s) / APK(s) › Build APK(s)**, then send them
`app/build/outputs/apk/release/app-release.apk` (or the `debug` one).

The release build is signed with your computer's debug key so it installs straight away. Build every
update from the **same computer**, otherwise friends' phones will refuse the update. If you ever publish
to the Play Store, create a proper key with **Build › Generate Signed App Bundle / APK** first.

## First run tips

- Choose **Precise** location and allow notifications (needed for plan reminders).
- Settings › Background recording: if it says **FIX**, set Meridian's Battery to **Unrestricted**.
  Some brands (Xiaomi, Oppo, Vivo, Huawei, Samsung) stop background apps aggressively.
- Do your first recording outside, since GPS indoors is weak.
- Allow **Physical activity** when asked, so steps are counted.
- Watches: Settings › **Watches & sensors**. For live heart rate, turn on heart-rate broadcasting on the watch,
  then tap Find a sensor. For Fitbit/Garmin/Samsung workouts, turn on Health Connect in the watch app, then tap
  Connect Health Connect in Meridian.

## Project layout

```
app/src/main/java/com/meridian/tracker/
  MeridianApp.kt            app setup, notification channels, reminders
  MainActivity.kt           permissions + navigation (Record · Activities · Plan · Progress)
  tracking/
    TrackProcessor.kt       GPS filtering, distance, moving time, splits
    ElevationTracker.kt     barometer/GPS climb with noise filtering
    Follow.kt               ghost runner and route following
    TrackingManager.kt      recording state, ghost / route / target guides
    TrackingService.kt      foreground service: GPS, barometer and steps with screen off
    HeartRateMonitor.kt     Bluetooth heart-rate sensors (standard Heart Rate profile)
    StepCounter.kt          steps and cadence from the phone's step sensor
  data/
    ActivityStore.kt        activities as JSON on the phone + crash snapshots
    ExtrasStore.kt          training plan, goals, saved routes, gear, badge history
    ActivityMedia.kt        activity photos and videos (stored privately on the phone)
    Gear.kt                 gear distance, remaining life and suggestions
    Plan.kt                 training plan generator, progress and adaptation
    PlanReminders.kt        morning reminders and evening nudges (WorkManager)
    Training.kt             relative effort, fitness & freshness, race predictor,
                            matched routes, goals, badges
    Stats.kt                periods, streaks, best efforts, personal bests, reviews
    ActivityAnalysis.kt     pace/elevation profiles
    DataTransfer.kt         import, export, backup, restore, auto-backup
    HeartRate.kt            heart-rate parsing, zones, effort, charts
    HealthSync.kt           Health Connect: watch workouts, heart rate, steps
    io/                     GPX/TCX/FIT readers, GPX writer, Strava CSV
    OfflineMaps.kt, Prefs.kt, TileMath.kt
  share/                    share images and sharing
  ui/                       theme, components, screens
app/src/test/               67 unit tests
```

Run the unit tests in Android Studio: right-click `app/src/test` › **Run Tests**.

## Credits

- Map data © OpenStreetMap contributors; basemap © CARTO (free for non-commercial use); maps by MapLibre
- Fonts: Instrument Sans and Geist Mono (SIL Open Font License, see `assets/licenses`)
- Training plans and predictions are general guidance, not medical advice.
