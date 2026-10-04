<img src="hero.png"/>

# Another Widget — personal fork

> This is a **personal fork** of [tommasoberlose/another-widget](https://github.com/tommasoberlose/another-widget).
> The upstream project is archived. This fork exists for my own use: it is not supported, not kept in
> sync with upstream, and not intended to be merged back. Use it, but expect it to fit my needs.

The application id is `cc.hikamous.anotherwidget`, so this build **installs next to the Play Store
version** instead of replacing it.

## What this fork changes

### 1. Weather: QWeather only

- **Removed every previous weather backend**: OpenWeatherMap, Weatherbit, WeatherAPI, HERE,
  AccuWeather, weather.gov and YR.no — including their repositories, Retrofit services, icon
  mappings, API-key settings and the "weather provider" picker.
- **Uses the QWeather hourly forecast** (`GET {apiHost}/weather/v1/hourly/{latitude}/{longitude}`).
- **Caches 24 hours of hourly data locally** and advances the widget from that cache, so the three
  cadences stay independent:

  | | |
  |---|---|
  | Widget display progression | 1 hour, from cache, no network |
  | QWeather request | 3 hours |
  | Location refresh | 6 hours |

- The displayed hour is selected from the cache by its `forecastTime`, so rain forecast for later
  today never shows a rain icon now. A cache that has run out of relevant hours is not displayed.
- Moving more than ~10 km invalidates the weather cache immediately.
- **A "Test" button in the settings** makes one real request with the values currently typed in (no
  need to save first) and reports either the temperature, condition and hour count, or a precise
  reason for failure: invalid key, wrong API host, quota, rate limit, app-restriction rejection,
  host not resolvable, timeout.
- Unknown condition codes fall back to a generic icon instead of leaving the widget blank.
- Transient failures keep the previously fetched weather on screen.

### 2. Location without Google Play services

- `FusedLocationProviderClient` was replaced with `android.location.LocationManager`
  (GPS / NETWORK providers probed at runtime).
- A single fix with a 15 second timeout, then the best last-known position.
- An expired location only means "refresh it" — the old coordinates stay usable, so a failed
  location refresh never costs you the weather.
- Manual location still bypasses the location subsystem entirely and needs no permission.
- `play-services-location` is **kept**: the Google Fit step counter still uses `ActivityRecognition`.

### 3. Backup & Restore (new)

- Settings → Backup & Restore, through Android's own file picker (SAF) — no storage permissions.
- Versioned, human readable JSON (`another-widget-backup-YYYY-MM-DD-HHmm.json`) with 83 settings.
- **Never included**: the API key, the weather cache, the location cache and other runtime state.
- Validated before anything is written, applied in a single preferences transaction, unknown fields
  ignored, individual invalid values skipped.

### 4. Housekeeping

- App id `cc.hikamous.anotherwidget`, version name `2.3.4-hubert.1`.
- `jcenter()` is gone, so the build resolves through Aliyun mirrors (slimadapter, switchbutton and
  providers-android only exist there).
- All new strings are translated in the 12 languages the project ships.

## Building

JDK 11 (Gradle 6.7.1 does not run on 21) and an Android SDK with platform 30 and build-tools 30.0.2.

Three local files are gitignored and must be provided:

| file | content |
|---|---|
| `local.properties` | `sdk.dir=/path/to/android/sdk` |
| `apikey.properties` | `GOOGLE_API_KEY="anything"` — the value **must be quoted** |
| `app/google-services.json` | any placeholder Firebase config for the package above |

```
./gradlew assembleDebug
./gradlew test                      # JVM tests
./gradlew connectedDebugAndroidTest # on-device tests
```

## Configuring the weather

Settings → Weather → QWeather, then fill in the **API host** and **API key** from your QWeather
console project, and press **Test** to confirm it works.

The API host is unique per project. A wrong one produces either "the API host could not be resolved"
or "the server turned the request away" — both are reported explicitly instead of as a generic
connection error.

The API key is deliberately **not** part of a backup, so after restoring on a new device it has to be
entered once.

## License

Copyright (C) 2017-2020 Tommaso Berlose (http://tommasoberlose.com)

Another Widget binaries and source code can be used according to the [MIT Licence](LICENSE).
