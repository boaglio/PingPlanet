# Google Play listing for PingPlanet

Copy these into Play Console. Limits are Google's; a check of the text fits all of them.

## Create app

| Field | Value |
|---|---|
| App name | PingPlanet: Network Speed Test |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free (cannot be changed to paid later) |
| Package name, set by the first upload | `io.github.boaglio.pingplanet` |

## Store settings

| Field | Value |
|---|---|
| Category | Tools |
| Tags | Choose the closest to "Internet" and "Tools" |
| Website | https://github.com/boaglio/PingPlanet |
| Email | Your choice. It is shown publicly |
| Privacy policy | https://github.com/boaglio/PingPlanet/blob/main/docs/PRIVACY.md |

## Main store listing

**App name (max 30):**

```
PingPlanet: Network Speed Test
```

**Short description (max 80):**

```
Test your internet against 25 servers worldwide and your local network speed.
```

**Full description (max 4000):**

```
PingPlanet measures your connection to public test servers in 20 countries, and the speed of your own local network.

INTERNET TEST
• Latency, jitter and download speed to 25 servers
• A live world map, colored by latency as each server answers
• See how far your connection really reaches, from the nearest server to the other side of the planet

LAN TEST
• Test the speed between this phone and another device on your network, like iperf
• Download, upload, latency and jitter
• Works with PingPlanet on another phone or with the free desktop app for Windows and Linux

SERVER
• Turn your phone into the test server, so another device can test against it

SHARE RESULTS
• Share a report of any test through the apps on your phone

CAREFUL WITH MOBILE DATA
Each download test can use a few hundred MB on a fast link. On a metered connection the app starts with downloads off and asks before using them. Latency-only tests use almost no data.

PRIVATE AND OPEN SOURCE
No account, no ads, no analytics. PingPlanet is free software under the GPL v3 license, and you can read every line of code on GitHub:
https://github.com/boaglio/PingPlanet

Test files are hosted by Vultr, Hetzner, OVH and Cloudflare. PingPlanet is not affiliated with them. Screenshots show example results.
```

**Graphics:**

| Field | File | Requirement |
|---|---|---|
| App icon | `icon-512.png` | 512×512 PNG |
| Feature graphic | `feature-graphic-1024x500.png` | 1024×500, no transparency |
| Phone screenshots | `screenshots/1…4` | 1080×1920; at least 2 needed |
| Tablet screenshots | None | Optional |
| Video | None | Optional |

**Release notes (max 500):**

```
First release on Google Play: internet and LAN speed tests, a live world map, a test server mode and shareable reports.
```

## App content

| Section | Answer |
|---|---|
| Privacy policy | The URL above |
| Ads | No, the app has no ads |
| App access | All functionality is available without special access |
| Content rating | Category "Utility, Productivity, Communication or Other"; answer **No** to every content question |
| Target audience | 13 and over (all ranges from 13–15 up), not designed for children |
| News app | No |
| Health apps | None |
| Financial features | None |
| Government app | No |
| Advertising ID | No, the app does not use it |

## Data safety

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |

The developer receives nothing: no accounts, analytics or crash reporting. The test servers see the IP address of the
phone connecting to them, like any website, and the privacy policy says so. The last LAN address stays on the phone.

## Release

The upload is `android/build/outputs/bundle/release/PingPlanet-release.aab`. Copy `android/release.env.example` to
`android/release.env`, fill it in, then build:

```sh
tools/android-release.sh
```

| Field | Value |
|---|---|
| Play App Signing | Use your existing key, so GitHub and Play versions can update each other |
| Release name | 0.1.1 (101) |
| Countries | All |
