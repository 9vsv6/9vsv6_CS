# Arabic Anime – CloudStream extensions

Two Arabic anime providers for [CloudStream 3](https://github.com/recloudstream/cloudstream):

| Provider | Site | Catalog | Playback |
|---|---|---|---|
| **WitAnime** | <https://witanime.site> | ✅ browse / search / episodes | ⚠️ depends on the site's stream gate (see below) |
| **Anime4Up** | <https://w1.anime4up.rest> | ✅ browse / search / episodes | ✅ direct HLS + supported hosts |

Everything is scraped from the public pages of both sites. The repo does not host any video files.

---

## 1. Build (automatic)

Every push to `main` runs the **Build** workflow (`.github/workflows/build.yml`), which compiles both
plugins and force-publishes the result to the `builds` branch:

- `builds/plugins.json`
- `builds/Witanime.cs3`, `builds/Anime4up.cs3`
- `builds/repo.json`, `builds/icon.png`

You can also trigger it manually from the **Actions** tab (**Build → Run workflow**).
The first run takes ~3 minutes and needs no secrets – it uses the default `GITHUB_TOKEN`.

> Do not add the repository to CloudStream before the `builds` branch exists, otherwise the download will fail.

## 2. Add the repository in CloudStream

In CloudStream: **Settings → Extensions → Add repository**, then paste:

```
https://raw.githubusercontent.com/9vsv6/9vsv6_CS/builds/repo.json
```

Open the new repository and install **WitAnime** and **Anime4Up**.

## 3. Build locally (optional)

Requirements: JDK 17+, Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```powershell
$env:ANDROID_HOME = "C:\path\to\android-sdk"
.\gradlew.bat make makePluginsJson
```

Output:

- `build/*.cs3` – installable plugin files
- `build/plugins.json` – index used by the repository

## How the providers work

### Anime4Up

- Search: `GET /?s=<query>`
- Anime page: `.anime-details-title`, `.anime-story`, `.anime-genres`, info rows, episode cards (paginated with `/page/N/`)
- Episode page: `#episode-servers li[data-watch]` – every server is passed to CloudStream's extractor registry
- The site's own players (`4b.*.shop/Anime4up-S1/...`, `share4max.com`, ...) embed `let streamUrl = "...m3u8"` in the page; when no bundled extractor matches, the plugin extracts that HLS URL itself.

### WitAnime

- Search: `GET /search?q=<query>`
- Anime page: `h1`, poster, genre pills, info grid, `/watch/<slug>/<n>` episode links
- Playback: the site exposes `POST /watch/<slug>/<n>/sources` (needs the `X-CSRF-TOKEN` from the page and a browser-like header set – including `sec-ch-ua*`). The returned tokens point at `/watch/stream-gate/<token>`, which the plugin resolves to the real host before handing it to CloudStream.

> **Known issue:** at the time of writing `https://witanime.site/watch/stream-gate/<token>` answers `404` for direct requests even with a valid session and browser headers, while the `/sources` manifest works. If the site keeps this behaviour, WitAnime lists the catalog and episodes but returns no playable links. Nothing client-side can fix that – it is the website's player endpoint. Browsing/searching stays fully functional, and playback starts working again as soon as the site restores the gate.

## Notes

- Both providers send a desktop Chrome header set because the sites check `sec-ch-ua`, `Accept-Language` and `Sec-Fetch-*` before serving some endpoints.
- Only public pages are used; no account is required for browsing.
- Movies are supported on both sites (`/movie/<slug>` on WitAnime, `/anime-type/movie-3/` on Anime4Up).
- Sites change domains regularly. If a provider stops loading, update `mainUrl` in the matching provider file:
  - `Witanime/src/main/kotlin/com/witanime/WitAnimeProvider.kt`
  - `Anime4up/src/main/kotlin/com/anime4up/Anime4UpProvider.kt`

## What has been verified

- Both modules compile and package against `com.lagradost:cloudstream3:pre-release` (Gradle 9.3.1, AGP 8.13.2, Kotlin 2.3.20).
- **Anime4Up:** search returns results, `Naruto` loads all 220 episodes (5 paginated pages), an episode exposes 8 servers, and the site's own player yields a valid `#EXTM3U` master playlist.
- **WitAnime:** search works, `Naruto` loads all 220 episodes, movie pages produce a single movie episode, and `POST /watch/<slug>/<n>/sources` returns real player tokens. Only the `/watch/stream-gate/<token>` step is currently broken on the site (see above).

## Disclaimer

This project is not affiliated with WitAnime or Anime4Up. It only indexes publicly available pages, in the same way a browser does. All content belongs to its respective owners.
