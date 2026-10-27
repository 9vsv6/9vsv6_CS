# Arabic Anime – CloudStream extensions

Three Arabic providers for [CloudStream 3](https://github.com/recloudstream/cloudstream):

| Provider | Site | Catalog | Playback |
|---|---|---|---|
| **WitAnime** | <https://witanime.site> | ✅ browse / search / episodes | ⚠️ depends on the site's stream gate (see below) |
| **Anime4Up** | <https://w1.anime4up.rest> | ✅ browse / search / episodes | ✅ direct HLS + supported hosts |
| **EgyDead** | <https://k8w4e.sbs> | ✅ movies / series / anime, browse / search / episodes | ✅ mirrors via the site's own player API |

Everything is scraped from the public pages of those sites. The repo does not host any video files.

---

## 1. Publish the repo (one time)

You need a **public** GitHub repository so CloudStream can reach the raw files.

```powershell
cd cloudstream-arabic-anime
git init
git add .
git commit -m "WitAnime + Anime4Up CloudStream plugins"
git branch -M main
git remote add origin https://github.com/<your-username>/cloudstream-arabic-anime.git
git push -u origin main
```

Then on GitHub:

1. Open **Settings → Actions → General** and make sure workflows are enabled.
2. Open the **Actions** tab and run the **Build** workflow once (or just push – it runs automatically).
3. The workflow compiles the plugins and publishes them to a `builds` branch:
   - `builds/plugins.json`
   - `builds/Witanime.cs3`, `builds/Anime4up.cs3`, `builds/EgyDead.cs3`
   - `builds/repo.json`, `builds/icon.png`

> The first run takes ~3 minutes. Do not add the repository to CloudStream before the `builds` branch exists, otherwise the download will fail.

## 2. Add the repository in CloudStream

In CloudStream: **Settings → Extensions → Add repository**, then paste:

```
https://raw.githubusercontent.com/<your-username>/cloudstream-arabic-anime/builds/repo.json
```

Open the new repository and install **WitAnime**, **Anime4Up** and **EgyDead**.

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

> **The site's own player is broken.** `https://witanime.site/watch/stream-gate/<token>` answers `404` for every episode, provider and session, even though `/sources` returns valid tokens. That is a server-side bug, not something a client can work around.
>
> **Fix (v2):** when the native player yields nothing, the provider takes the slug from the episode URL (`/watch/black-clover-2nd-season/4` → `black-clover-2nd-season`), finds the same title on Anime4Up (exact slug → prefix → substring → direct `/anime/<slug>/` guess), locates the same episode number and returns those streams, labelled `WitAnime via Anime4Up`. Anime4Up's HLS plays without any special headers, so this works from CloudStream and Harbor alike.
>
> Episodes that Anime4Up does not carry still return nothing; the log line in that case is `no matching Anime4Up title`.

### EgyDead

- Search: `GET /?s=<query>` – the cards live in `ul.posts-list li.movieItem` (same markup as every archive page).
- Listings: home (`/`, paginated with `/page/N/`), `/category/english-movies/`, `/departments/series/`, `/series-category/anime-series/`.
- Page type is read from the page's JSON-LD: `Movie`, `TVEpisode`, `TVSeason` or `TVSeries`, which decides whether the entry becomes a movie, a season with episodes or a show with all seasons merged.
- Episodes: `.EpsList li a[href*="/episodes/"]` on a season page; an episode page is resolved through its breadcrumb back to its season, so opening one episode shows the whole season.
- Playback: `<post>/watch/` exposes `ul.serversList li[data-link]`. Every server is first offered to CloudStream's extractor registry. The site's own **MegaMax** player (`q7v3k8m2p1.top/iframe/<id>`, a Laravel + Inertia SPA) is then asked for its `streams` prop with the Inertia headers (`X-Inertia`, `X-Inertia-Version`, `X-Inertia-Partial-Data: streams`), which returns every quality/mirror pair (StreamWish, MixDrop, DoodStream, Vidmoly, Voe, VidHide, Vidara, …) as JSON. Those mirrors are handed to the extractor registry too, with the quality label applied. A raw `m3u8`/`mp4` scan is the last resort for players nothing recognises.

## Notes

- All providers send a desktop Chrome header set because the sites check `sec-ch-ua`, `Accept-Language` and `Sec-Fetch-*` before serving some endpoints.
- Only public pages are used; no account is required for browsing.
- Movies are supported on all three sites (`/movie/<slug>` on WitAnime, `/anime-type/movie-3/` on Anime4Up, plain post pages on EgyDead).
- Sites change domains regularly. If a provider stops loading, update `mainUrl` in the matching provider file:
  - `Witanime/src/main/kotlin/com/witanime/WitAnimeProvider.kt`
  - `Anime4up/src/main/kotlin/com/anime4up/Anime4UpProvider.kt`
  - `EgyDead/src/main/kotlin/com/egydead/EgyDeadProvider.kt`

## What has been verified

- All three modules compile and package against `com.lagradost:cloudstream3:pre-release` (Gradle 9.3.1, AGP 8.13.2, Kotlin 2.3.20).
- **Anime4Up:** search returns results, `Naruto` loads all 220 episodes (5 paginated pages), an episode exposes 8 servers, and the site's own player yields a valid `#EXTM3U` master playlist.
- **WitAnime:** search works, `Naruto` loads all 220 episodes, movie pages produce a single movie episode, and `POST /watch/<slug>/<n>/sources` returns real player tokens. Only the `/watch/stream-gate/<token>` step is currently broken on the site (see above).
- **EgyDead:** the home page and all three category pages return 48 cards per page and paginate, search works, movie pages produce a `Movie` entry with poster/plot/year/genres, an episode is resolved to its season (Naruto Shippuden: 500 episodes), a `/Series/` page merges all 7 Naruto seasons (1988 episodes) and a `/film-series/` collection lists its 10 movies. The MegaMax player API returns 24 quality/mirror pairs for an episode, 19 of which match a bundled CloudStream extractor (StreamHG, Vidmoly, MixDrop, DoodStream, Vidara, EarnVids, Voe); pages using direct servers expose them as separate `serversList` entries.

## Automated smoke test

`tools/smoke-test.mjs` hits the same endpoints and applies the same parsing rules as the plugins, so a failure there means the provider will fail in CloudStream/Harbor too:

```bash
node tools/smoke-test.mjs              # both providers
node tools/smoke-test.mjs anime4up     # only Anime4Up
node tools/smoke-test.mjs witanime     # only WitAnime
```

Example output (10/10 today):

```
== WitAnime ==
[PASS] search page — 25 unique titles
[PASS] anime page + episode list — 220 episodes (1..220)
[PASS] watch page + csrf token — token found
[PASS] sources manifest — 5 servers
[KNOWN] stream gate (site player) — site returns 404 — playback blocked server-side

== Anime4Up ==
[PASS] search page — 6 unique titles
[PASS] anime page + episode list — 48 episodes on page 1, 5 pages
[PASS] episode page + servers — 8 servers
[PASS] internal player → HLS url — cdn1.7p4bm32.shop
[PASS] HLS plays without headers — 200 application/vnd.apple.mpegurl; charset=UTF-8
```

It runs on demand via `.github/workflows/smoke-test.yml` (Actions → **Smoke test** → Run workflow).
Please run it locally rather than relying on CI: GitHub-hosted runners are in datacenter IP ranges that
both sites block, so the checks pass on a home connection and fail from CI.

## Disclaimer

This project is not affiliated with WitAnime, Anime4Up or EgyDead. It only indexes publicly available pages, in the same way a browser does. All content belongs to its respective owners.
