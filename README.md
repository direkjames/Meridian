# Meridian

A private fitness tracker for Android. Record runs, walks, hikes and rides, and keep everything on your phone.

![Status: Active](https://img.shields.io/badge/status-active-brightgreen)
![Version 2.0](https://img.shields.io/badge/version-2.0-blue)
![Platform: Android](https://img.shields.io/badge/platform-Android-3DDC84)
![Android 8.0 and up](https://img.shields.io/badge/Android-8.0%2B-informational)

## About

Meridian tracks your activities and helps you train. It works without an account and without a feed. Your activities stay on your phone unless you choose to share or back them up. Use it to record a session, follow a route, plan a month of workouts, or train for an event.

## Features

**Recording**
- Record runs, trail runs, treadmill sessions, walks, hikes and rides.
- Keeps recording with the screen off. Pause, resume and finish from the lock screen.
- Live distance, time, pace or speed, climb and moving time.
- Follow a route you drew, with the distance to go and an alert if you leave it.
- Race your ghost: see your earlier effort on the same route as a dot on the map.
- See today's plan target as a progress bar while you move.
- Get a recording back if your phone closes the app by accident.

**Heart rate, watches and steps**
- Live heart rate from chest straps and watches that broadcast it.
- Bring in workouts from Fitbit, Garmin, Samsung, Pixel Watch and others through Health Connect.
- Steps, cadence and stride on runs and walks.
- Heart rate chart, time in zones and an effort score for every activity.

**Activities**
- A history you can filter, with a weekly chart.
- Route map, splits, and pace and elevation charts you can scrub.
- Calories, notes, effort rating and how you felt.
- Photos and videos on any activity, and gear that tracks how far your shoes have gone.
- See how you compare on routes you repeat, and get a card when you set a personal best or earn a badge.
- Rename, delete, change the sport, or trim a forgotten start or stop.

**Training plans**
- **Event plan:** train for a fun run or race from 3K to a marathon. Run, walk or run-walk, with distance or time targets, a lighter week every fourth week and a taper before the event.
- **Monthly plan:** get a month of walking, running, run-walk, hiking and riding, with no event needed. Pick your goal, sports, days per week, free days and long day.
- Every month is different. Each month has a new theme and a different mix of sessions, and the total changes from month to month.
- A month calendar shows each day's session and fills in as you record.
- A chart compares what was planned with what you did each week, with a plain "on track" check.
- Missed a session? Meridian suggests a free day to catch up, and a moved session does not count as missed.
- Edit any plan: change a day's type, sport, distance or time, move it, make it a rest day, or add a session. Risky changes come with a warning, and you can reset a day or the whole plan.
- Suggest a route for any monthly session: the route planner opens with that session's distance already filled in.
- Daily reminders for both plans, with an optional evening nudge if today's session is not done. Monthly plans also preview the week ahead.
- At the end of a month, a review shows how it went and sizes the next month to match.

**Progress**
- Week, month and year totals with charts and comparison to the last period.
- Goals for distance, time or activity count, shown as progress rings.
- Fitness and freshness over the last 90 days, and a suggested weekly effort range.
- Race predictions for 5K, 10K, half marathon and marathon.
- Personal bests, streaks, 30 badges and a heatmap of everywhere you have been.
- Year in Review and Month in Review stories you can share.

**Maps and routes**
- Draw a route by tapping, as a loop or out and back, then save it and follow it.
- Plan a route from a distance, on foot or by bike. You get up to four routes to choose from.
- Download map areas for offline use.

**Sharing and privacy**
- Share stickers and cards of your stats or route, or open them straight in Instagram.
- Privacy zones hide your home or work from shared routes and exported files.
- Weather for each activity, which you can turn off.
- Light, dark or system theme.

**Your data**
- Import your Strava archive or single GPX, TCX and FIT files.
- Export to GPX, back up and restore, or back up automatically to a file you pick.
- Kilometres or miles.

## Getting started

1. Install the latest Android Studio.
2. Open the `Meridian` folder with **File > Open** and wait for the sync to finish.
3. Plug in your phone with USB debugging on, then press **Run**.
4. To share an APK, choose **Build > Build App Bundle(s) / APK(s) > Build APK(s)**, then send `app/build/outputs/apk/release/app-release.apk`. Build every update from the same computer, or phones will refuse it.
5. On first launch, choose **Precise** location and allow notifications and physical activity.
6. In Settings > Background recording, set Meridian's battery use to **Unrestricted** if it says FIX.
7. For watches, open Settings > Watches & sensors and follow the steps.

## Requirements

- A phone running Android 8.0 or newer
- Android Studio, to build the app
- GPS for outdoor recording. The internet is needed for maps, route planning and weather.

## Built with

- Kotlin and Jetpack Compose
- MapLibre with OpenStreetMap data
- WorkManager for reminders

## Status

Active.

## Credits

- Map data from OpenStreetMap contributors. Basemap from CARTO.
- Fonts: Instrument Sans and Geist Mono.
- Training plans and predictions are general guidance, not medical advice.
- Author: direk james
- License: all rights reserved. See the LICENSE file.
