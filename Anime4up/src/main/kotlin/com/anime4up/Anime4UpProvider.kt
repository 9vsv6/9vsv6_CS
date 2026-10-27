package com.anime4up

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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

class Anime4Up : MainAPI() {
    override var mainUrl = "https://w1.anime4up.rest"
    override var name = "Anime4Up"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val listPath = "/%D9%82%D8%A7%D8%A6%D9%85%D8%A9-%D8%A7%D9%84%D8%A7%D9%86%D9%85%D9%8A/"
    private val moviesPath = "/anime-type/movie-3/"

    override val mainPage = mainPageOf(
        "latest" to "أحدث الحلقات",
        "list" to "قائمة الأنمي",
        "movies" to "أفلام",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val STREAM_VAR_REGEX =
            Regex("""(?:let|var|const)\s+streamUrl\s*=\s*["']([^"']+)["']""")

        private val VIDEO_REGEX = Regex("""https?://[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?""")

        private val IFRAME_REGEX = Regex("""(?i)<iframe[^>]+src=["']([^"']+)["']""")
    }

    private fun headers(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        if (referer != null) put("Referer", referer)
    }

    // ------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "latest" -> mainUrl
            "list" -> pageUrl(listPath, page)
            else -> pageUrl(moviesPath, page)
        }

        val document = app.get(url, headers = headers()).document
        val items = when (request.data) {
            "latest" -> parseEpisodeCards(document, 40)
            else -> parseAnimeCards(document, 40)
        }

        return newHomePageResponse(request.name, items, hasNext = request.data != "latest" && page < 30)
    }

    private fun pageUrl(path: String, page: Int): String {
        val base = "$mainUrl$path"
        return if (page <= 1) base else "${base}page/$page/"
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", headers = headers()).document
        return parseAnimeCards(document, 40)
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers()).document

        val title = document.selectFirst(".anime-details-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst(".anime-thumbnail img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst(".anime-story")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select(".anime-genres a").map { it.text().trim() }
            .filter { it.isNotBlank() }

        val info = parseInfoRows(document)
        val year = info["بداية العرض"]?.filter { it.isDigit() }?.toIntOrNull()
        val showStatus = when {
            info["حالة الأنمي"]?.contains("مكتمل") == true -> ShowStatus.Completed
            info["حالة الأنمي"]?.contains("مستمر") == true -> ShowStatus.Ongoing
            else -> null
        }

        val episodes = fetchEpisodes(url, document)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.showStatus = showStatus
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // -------------------------------------------------------------- episodes

    private suspend fun fetchEpisodes(url: String, firstPage: Document): List<com.lagradost.cloudstream3.Episode> {
        val episodes = LinkedHashMap<Int, com.lagradost.cloudstream3.Episode>()

        fun collect(document: Document) {
            document.select(".anime-card-themex").forEach { card ->
                val anchor = card.selectFirst("a[href*=\"/episode/\"]") ?: return@forEach
                val href = anchor.attr("href")
                val number = card.selectFirst(".ep_num")?.text()
                    ?.filter { it.isDigit() }?.toIntOrNull()
                    ?: href.trimEnd('/').substringAfterLast('-').filter { it.isDigit() }.toIntOrNull()
                    ?: return@forEach
                if (episodes.containsKey(number)) return@forEach
                episodes[number] = newEpisode(href) {
                    this.name = "الحلقة $number"
                    this.episode = number
                }
            }
        }

        collect(firstPage)

        val lastPage = firstPage.select(".episodes-pagination-holder a")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .maxOrNull() ?: 1

        val base = url.trimEnd('/')
        for (page in 2..minOf(lastPage, 20)) {
            val document = try {
                app.get("$base/page/$page/", headers = headers(url)).document
            } catch (_: Exception) {
                continue
            }
            collect(document)
        }

        return episodes.values.sortedBy { it.episode ?: 0 }
    }

    // ------------------------------------------------------------- load links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = try {
            app.get(data, headers = headers(mainUrl)).document
        } catch (_: Exception) {
            return false
        }

        var found = false

        document.select("#episode-servers li[data-watch]").forEach { element ->
            val watchUrl = element.attr("data-watch").trim()
            if (watchUrl.isBlank()) return@forEach

            val serverName = element.selectFirst(".watch-server-name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "server"
            val quality = element.selectFirst(".quality")?.text()?.trim().orEmpty()

            val extracted = try {
                loadExtractor(watchUrl, data, subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
            if (extracted) {
                found = true
                return@forEach
            }

            if (extractGeneric(watchUrl, data, serverName, quality, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }

    /**
     * Fallback for the website's own player pages (Anime4up-S1/S2, share4max, ...)
     * which embed the HLS url directly in a script tag.
     */
    private suspend fun extractGeneric(
        url: String,
        referer: String,
        serverName: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = try {
            app.get(url, headers = headers(referer), timeout = 30)
        } catch (_: Exception) {
            return false
        }
        if (response.code != 200) return false

        val html = response.text
        var emitted = false

        // The site's own player stores the HLS master playlist in `let streamUrl = "..."`.
        // Those URLs are token based and have no .m3u8 suffix, so they must be
        // reported as M3U8 explicitly.
        val streamUrl = STREAM_VAR_REGEX.find(html)?.groupValues?.get(1)
        if (!streamUrl.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} | $serverName",
                    url = streamUrl.replace("\\/", "/"),
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = url
                    this.quality = qualityValue(quality)
                    this.headers = mapOf("User-Agent" to UA, "Referer" to url)
                }
            )
            emitted = true
        }

        val candidates = mutableListOf<String>()
        VIDEO_REGEX.findAll(html).forEach { candidates.add(it.value) }
        IFRAME_REGEX.findAll(html).forEach { match ->
            val src = match.groupValues[1]
            when {
                src.startsWith("//") -> candidates.add("https:$src")
                src.startsWith("http") -> candidates.add(src)
            }
        }

        for (candidate in candidates.map { it.replace("\\/", "/").trim() }.filter { it.isNotBlank() }.distinct()) {
            if (candidate == streamUrl) continue
            if (candidate.contains(".m3u8") || candidate.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} | $serverName",
                        url = candidate,
                        type = if (candidate.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = url
                        this.quality = qualityValue(quality)
                        this.headers = mapOf("User-Agent" to UA, "Referer" to url)
                    }
                )
                emitted = true
            } else {
                val nested = try {
                    loadExtractor(candidate, url, subtitleCallback, callback)
                } catch (_: Exception) {
                    false
                }
                if (nested) emitted = true
            }
        }
        return emitted
    }

    private fun qualityValue(quality: String?): Int = when (quality?.trim()?.lowercase()) {
        "4k", "2160p" -> Qualities.P2160.value
        "fhd", "1080p" -> Qualities.P1080.value
        "hd", "720p" -> Qualities.P720.value
        "sd", "480p", "360p" -> Qualities.P480.value
        else -> getQualityFromName(quality)
    }

    // --------------------------------------------------------------- parsing

    private fun parseInfoRows(document: Document): Map<String, String> {
        val info = LinkedHashMap<String, String>()
        document.select(".anime-info").forEach { row ->
            val text = row.text().trim()
            val separator = text.indexOf(':')
            if (separator > 0) {
                val key = text.substring(0, separator).trim()
                val value = text.substring(separator + 1).trim()
                if (key.isNotBlank() && value.isNotBlank()) info[key] = value
            }
        }
        return info
    }

    private fun parseAnimeCards(document: Document, limit: Int): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()

        document.select(".anime-card-themex").forEach { card ->
            val anchor = card.selectFirst("a[href*=\"/anime/\"]") ?: return@forEach
            val href = anchor.attr("href")
            val slug = href.substringAfter("/anime/", "").trimEnd('/')
            if (slug.isBlank() || results.containsKey(slug)) return@forEach

            val image = card.selectFirst("img")
            val title = card.selectFirst(".anime-card-title a")?.text()?.trim()
                ?: image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: return@forEach

            results[slug] = newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image?.attr("src") ?: image?.attr("data-image")
            }
        }

        return results.values.take(limit)
    }

    private fun parseEpisodeCards(document: Document, limit: Int): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()

        document.select("a[href*=\"/episode/\"]").forEach { anchor ->
            val animeAnchor = anchor.parents()
                .firstOrNull { it.selectFirst("a[href*=\"/anime/\"]") != null }
                ?.selectFirst("a[href*=\"/anime/\"]")
                ?: return@forEach

            val href = animeAnchor.attr("href")
            val slug = href.substringAfter("/anime/", "").trimEnd('/')
            if (slug.isBlank() || results.containsKey(slug)) return@forEach

            val card = anchor.parents().firstOrNull { it.hasClass("anime-card-themex") }
            val image = card?.selectFirst("img")
            val title = card?.selectFirst(".anime-card-title a")?.text()?.trim()
                ?: image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: return@forEach

            results[slug] = newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image?.attr("src") ?: image?.attr("data-image")
            }
        }

        return results.values.take(limit)
    }
}
