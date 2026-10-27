package com.witanime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "latest" to "أحدث الحلقات",
        "trending" to "الأكثر مشاهدة",
        "movies" to "أفلام",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val CLIENT_HINTS = mapOf(
            "sec-ch-ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
        )

        private val VIDEO_REGEX = Regex("""https?://[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?""")
        private val IFRAME_REGEX = Regex("""(?i)<iframe[^>]+src=["']([^"']+)["']""")
        private val STREAM_VAR_REGEX = Regex("""(?:let|var|const)\s+streamUrl\s*=\s*["']([^"']+)["']""")

        private const val A4U_BASE = "https://w1.anime4up.rest"
        private val A4U_STREAM_REGEX = Regex("""(?:let|var|const)\s+streamUrl\s*=\s*["']([^"']+)["']""")
    }

    // ---------------------------------------------------------------- headers

    private fun browserHeaders(referer: String? = null): Map<String, String> = buildMap {
        putAll(CLIENT_HINTS)
        put("User-Agent", UA)
        put(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        put("Sec-Fetch-Dest", "document")
        put("Sec-Fetch-Mode", "navigate")
        put("Sec-Fetch-Site", if (referer == null) "none" else "same-origin")
        put("Sec-Fetch-User", "?1")
        put("Upgrade-Insecure-Requests", "1")
        if (referer != null) put("Referer", referer)
    }

    private fun ajaxHeaders(referer: String, csrf: String): Map<String, String> = buildMap {
        putAll(CLIENT_HINTS)
        put("User-Agent", UA)
        put("Accept", "application/json, text/plain, */*")
        put("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        put("X-CSRF-TOKEN", csrf)
        put("X-Requested-With", "XMLHttpRequest")
        put("Origin", mainUrl)
        put("Referer", referer)
        put("Sec-Fetch-Dest", "empty")
        put("Sec-Fetch-Mode", "cors")
        put("Sec-Fetch-Site", "same-origin")
    }

    private fun gateHeaders(referer: String): Map<String, String> {
        val headers = browserHeaders(referer).toMutableMap()
        headers["Sec-Fetch-Dest"] = "iframe"
        return headers
    }

    // ------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "movies" -> "$mainUrl/movies"
            else -> mainUrl
        }
        val document = app.get(url, headers = browserHeaders()).document

        val items = when (request.data) {
            "latest" -> parseEpisodeCards(document, 30)
            "trending" -> parseAnimeCards(document, 30)
            else -> parseAnimeCards(document, 40)
        }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search?q=$encoded", headers = browserHeaders()).document
        return parseAnimeCards(document, 60)
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders()).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val slug = url.trimEnd('/').substringAfterLast('/')

        val poster = document.selectFirst("main img[src*=\"/posters/\"]")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val info = parseInfoRows(document)
        val tags = parseTags(document)
        val plot = document.selectFirst("meta[name=description]")?.attr("content")

        val status = when {
            document.selectFirst("span:contains(مكتمل)") != null -> ShowStatus.Completed
            document.selectFirst("span:contains(مستمر)") != null -> ShowStatus.Ongoing
            else -> null
        }

        val episodes = parseEpisodes(document, url, slug)
        val type = if (url.contains("/movie/")) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.year = info["السنة"]?.toIntOrNull()
            this.tags = tags
            this.plot = plot
            this.showStatus = status
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // -------------------------------------------------------------- episodes

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val watchUrl = data
        val watch = try {
            app.get(watchUrl, headers = browserHeaders(mainUrl))
        } catch (_: Exception) {
            return false
        }
        if (watch.code != 200) return false

        if (tryNativeSources(watchUrl, watch.text, watch.cookies, subtitleCallback, callback)) {
            return true
        }

        // The website's own player (/watch/stream-gate/<token>) answers 404 even for
        // a valid session, so resolve the same title + episode through Anime4Up.
        return fallbackToAnime4Up(watchUrl, subtitleCallback, callback)
    }

    private suspend fun tryNativeSources(
        watchUrl: String,
        pageHtml: String,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val csrf = Regex("<meta name=\"csrf-token\" content=\"([^\"]+)\"")
            .find(pageHtml)?.groupValues?.get(1) ?: return false

        val manifest = try {
            app.post(
                "$watchUrl/sources",
                headers = ajaxHeaders(watchUrl, csrf),
                cookies = cookies,
                allowRedirects = true,
                timeout = 30,
            )
        } catch (_: Exception) {
            return false
        }
        if (manifest.code != 200) return false

        val players = try {
            JSONObject(manifest.text).optJSONObject("players") ?: JSONObject()
        } catch (_: Exception) {
            return false
        }

        var found = false
        val qualities = players.keys()
        while (qualities.hasNext()) {
            val quality = qualities.next()
            val servers = players.optJSONArray(quality) ?: continue
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                val token = server.optString("token")
                if (token.isBlank()) continue
                val label = server.optString("label").ifBlank { "server" }

                val candidates = try {
                    resolveServer(watchUrl, token, cookies)
                } catch (_: Exception) {
                    emptyList()
                }

                for (candidate in candidates) {
                    if (isVideoUrl(candidate)) {
                        emitVideo(candidate, quality, label, watchUrl, callback)
                        found = true
                    } else if (candidate.startsWith("http")) {
                        val extracted = try {
                            com.lagradost.cloudstream3.utils.loadExtractor(
                                candidate,
                                watchUrl,
                                subtitleCallback,
                                callback,
                            )
                        } catch (_: Exception) {
                            false
                        }
                        if (extracted) found = true
                    }
                }
            }
        }
        return found
    }

    // ------------------------------------------------- Anime4Up fallback

    private fun a4uHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        if (referer != null) put("Referer", referer)
    }

    /**
     * WitAnime's player gate is broken server-side, so the same title and episode
     * number are looked up on Anime4Up and those streams are returned instead.
     */
    private suspend fun fallbackToAnime4Up(
        watchUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // /watch/black-clover-2nd-season/4        -> black-clover-2nd-season
        // /watch/movie/the-last-naruto-the-movie  -> the-last-naruto-the-movie
        val path = watchUrl.substringAfter("/watch/", "")
        if (path.isBlank()) return false
        val slug = if (path.startsWith("movie/")) {
            path.removePrefix("movie/").trimEnd('/')
        } else {
            path.substringBefore("/")
        }
        if (slug.isBlank()) return false

        val episode = watchUrl.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: 1
        val query = URLEncoder.encode(slug.replace('-', ' '), "UTF-8")

        val searchPage = try {
            app.get("$A4U_BASE/?s=$query", headers = a4uHeaders()).document
        } catch (_: Exception) {
            return false
        }

        var animeUrl: String? = null
        var bestScore = Int.MAX_VALUE
        for (card in searchPage.select(".anime-card-themex")) {
            val href = card.selectFirst("a[href*=\"/anime/\"]")?.attr("href") ?: continue
            val candidate = href.substringAfter("/anime/", "").trimEnd('/')
            if (candidate.isBlank()) continue
            val score = slugScore(slug, candidate)
            if (score < bestScore) {
                bestScore = score
                animeUrl = href
            }
        }
        if (bestScore > 2) animeUrl = null

        // Search can miss titles that exist; try the slug directly.
        if (animeUrl == null) {
            val guesses = linkedSetOf(slug)
            if (slug.endsWith("-movie")) guesses.add(slug.removeSuffix("-movie"))
            for (candidate in guesses) {
                val direct = try {
                    app.get("$A4U_BASE/anime/$candidate/", headers = a4uHeaders())
                } catch (_: Exception) {
                    null
                }
                if (direct != null && direct.code == 200 && direct.url.contains("/anime/")) {
                    animeUrl = direct.url
                    break
                }
            }
        }

        val anime = animeUrl ?: return false
        val animePage = try {
            app.get(anime, headers = a4uHeaders()).document
        } catch (_: Exception) {
            return false
        }

        var episodeUrl = findEpisodeUrl(animePage, episode)
        if (episodeUrl == null) {
            val lastPage = animePage.select(".episodes-pagination-holder a")
                .mapNotNull { it.text().trim().toIntOrNull() }
                .maxOrNull() ?: 1
            val total = animePage.select(".anime-info")
                .firstOrNull { it.text().contains("عدد الحلقات") }
                ?.text()?.filter { it.isDigit() }?.toIntOrNull()

            val guesses = LinkedHashSet<Int>()
            // Anime4Up lists ~48 episodes per page, newest first.
            if (total != null && total >= episode) {
                guesses.add(((total - episode) / 48) + 1)
            }
            for (pageNumber in 2..minOf(lastPage, 30)) guesses.add(pageNumber)

            val base = anime.trimEnd('/')
            for (pageNumber in guesses) {
                if (pageNumber < 2 || pageNumber > lastPage) continue
                val next = try {
                    app.get("$base/page/$pageNumber/", headers = a4uHeaders(anime)).document
                } catch (_: Exception) {
                    continue
                }
                episodeUrl = findEpisodeUrl(next, episode)
                if (episodeUrl != null) break
            }
        }

        val epUrl = episodeUrl ?: return false
        val epPage = try {
            app.get(epUrl, headers = a4uHeaders(anime)).document
        } catch (_: Exception) {
            return false
        }

        var emitted = false
        for (server in epPage.select("#episode-servers li[data-watch]")) {
            val serverUrl = server.attr("data-watch").trim()
            if (serverUrl.isBlank()) continue
            val serverName = server.selectFirst(".watch-server-name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "server"
            val quality = server.selectFirst(".quality")?.text()?.trim().orEmpty()

            val extracted = try {
                com.lagradost.cloudstream3.utils.loadExtractor(serverUrl, epUrl, subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
            if (extracted) {
                emitted = true
                continue
            }

            val playerPage = try {
                app.get(serverUrl, headers = a4uHeaders(epUrl), timeout = 30).text
            } catch (_: Exception) {
                ""
            }
            val stream = A4U_STREAM_REGEX.find(playerPage)?.groupValues?.get(1) ?: continue

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} via Anime4Up | $serverName",
                    url = stream.replace("\\/", "/"),
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = serverUrl
                    this.quality = qualityValue(quality)
                    this.headers = mapOf("User-Agent" to UA, "Referer" to serverUrl)
                }
            )
            emitted = true
        }
        return emitted
    }

    private fun slugScore(wanted: String, candidate: String): Int {
        val a = wanted.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val b = candidate.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return when {
            a.isBlank() || b.isBlank() -> Int.MAX_VALUE
            a == b -> 0
            b.startsWith(a) || a.startsWith(b) -> 1
            b.contains(a) || a.contains(b) -> 2
            else -> Int.MAX_VALUE
        }
    }

    private fun findEpisodeUrl(document: Document, number: Int): String? {        for (card in document.select(".anime-card-themex")) {
            val parsed = card.selectFirst(".ep_num")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            if (parsed == number) {
                val href = card.selectFirst("a[href*=\"/episode/\"]")?.attr("href")
                if (!href.isNullOrBlank()) return href
            }
        }
        return null
    }

    /**
     * Resolves one of the source tokens returned by the player manifest.
     *
     * The website mounts the player inside an iframe that points at
     * /watch/stream-gate/{token}; that endpoint answers with either a redirect to
     * the actual host or a small HTML wrapper that embeds it.
     */
    private suspend fun resolveServer(
        watchUrl: String,
        token: String,
        cookies: Map<String, String>,
    ): List<String> {
        val gateUrl = "$mainUrl/watch/stream-gate/$token"
        val response = app.get(
            gateUrl,
            headers = gateHeaders(watchUrl),
            cookies = cookies,
            allowRedirects = true,
            timeout = 30,
        )
        if (response.code != 200) return emptyList()

        val resolved = mutableListOf<String>()

        val finalUrl = response.url
        if (finalUrl != gateUrl && isVideoUrl(finalUrl)) {
            resolved.add(finalUrl)
        }

        val html = response.text

        STREAM_VAR_REGEX.find(html)?.groupValues?.get(1)?.let { resolved.add(it) }

        VIDEO_REGEX.findAll(html).forEach { match ->
            resolved.add(match.value.replace("\\/", "/"))
        }

        IFRAME_REGEX.findAll(html).forEach { match ->
            val src = match.groupValues[1].replace("\\/", "/")
            when {
                src.startsWith("//") -> resolved.add("https:$src")
                src.startsWith("http") -> resolved.add(src)
            }
        }

        return resolved
            .map { it.replace("\\/", "/").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun emitVideo(
        url: String,
        quality: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "${this.name} | $label",
                url = url,
                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = qualityValue(quality)
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to referer,
                    "Origin" to mainUrl,
                )
            }
        )
    }

    private fun qualityValue(quality: String?): Int = when (quality?.trim()?.lowercase()) {
        "4k", "2160p" -> Qualities.P2160.value
        "fhd", "1080p" -> Qualities.P1080.value
        "hd", "720p" -> Qualities.P720.value
        "sd", "480p", "360p" -> Qualities.P480.value
        else -> getQualityFromName(quality)
    }

    private fun isVideoUrl(url: String): Boolean =
        url.contains(".m3u8") || url.contains(".mp4")

    // --------------------------------------------------------------- parsing

    private fun parseEpisodeCards(document: Document, limit: Int): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()

        document.select("a[href*=\"/watch/\"]").forEach { anchor ->
            val href = anchor.attr("href").substringBefore("?")
            val path = href.substringAfter("/watch/", "")
            val isMovie = path.startsWith("movie/")
            val slug = if (isMovie) path.removePrefix("movie/").trimEnd('/') else path.substringBefore("/")
            if (slug.isBlank() || results.containsKey(slug)) return@forEach

            val image = anchor.selectFirst("img")
            val title = image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("h3")?.text()?.trim()
                ?: return@forEach

            val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
            val url = if (isMovie) "$mainUrl/movie/$slug" else "$mainUrl/anime/$slug"
            results[slug] = newAnimeSearchResponse(title, url, type) {
                this.posterUrl = image?.attr("src")
            }
        }

        return results.values.take(limit)
    }

    private fun parseAnimeCards(document: Document, limit: Int): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()

        var anchors = document.select("a[data-preview]")
        if (anchors.isEmpty()) anchors = document.select("a[href*=\"/anime/\"], a[href*=\"/movie/\"]")

        anchors.forEach { anchor ->
            val href = anchor.attr("href").substringBefore("?").trimEnd('/')
            val isMovie = href.contains("/movie/")
            val slug = if (isMovie) {
                href.substringAfter("/movie/", "").substringBefore("/")
            } else {
                href.substringAfter("/anime/", "").substringBefore("/")
            }
            if (slug.isBlank() || results.containsKey(href)) return@forEach

            val image = anchor.selectFirst("img")
            val title = image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("h3")?.text()?.trim()
                ?: return@forEach

            val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
            results[href] = newAnimeSearchResponse(title, href, type) {
                this.posterUrl = image?.attr("src")
            }
        }

        return results.values.take(limit)
    }

    private fun parseInfoRows(document: Document): Map<String, String> {
        val info = LinkedHashMap<String, String>()
        document.select("div.grid span.text-neutral-400").forEach { span ->
            val key = span.text().trim()
            val value = span.nextElementSibling()?.text()?.trim()
            if (key.isNotBlank() && !value.isNullOrBlank()) info[key] = value
        }
        return info
    }

    private fun parseTags(document: Document): List<String> {
        val grid = document.selectFirst("span:contains(الاستوديو)")
            ?.parents()
            ?.firstOrNull { it.hasClass("grid") }
            ?: return emptyList()

        val container = grid.previousElementSibling() ?: return emptyList()
        return container.select("span")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("حلق") }
            .distinct()
    }

    private fun parseEpisodes(
        document: Document,
        url: String,
        slug: String,
    ): List<com.lagradost.cloudstream3.Episode> {
        if (url.contains("/movie/")) {
            val href = document.selectFirst("a[href*=\"/watch/movie/\"]")?.attr("href")
                ?: return emptyList()
            return listOf(
                newEpisode(href) {
                    this.name = "الفيلم"
                    this.episode = 1
                }
            )
        }

        val episodes = LinkedHashMap<Int, com.lagradost.cloudstream3.Episode>()

        document.select("a[href*=\"/watch/$slug/\"]").forEach { anchor ->
            val href = anchor.attr("href")
            val number = href.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: return@forEach
            if (episodes.containsKey(number)) return@forEach

            episodes[number] = newEpisode(href) {
                this.name = "الحلقة $number"
                this.episode = number
            }
        }

        return episodes.values.sortedBy { it.episode ?: 0 }
    }
}
