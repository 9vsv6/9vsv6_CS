package com.egydead

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

/**
 * EgyDead (ايجي ديد) – Arabic movies / series / anime.
 *
 * Listing, post and watch pages are plain WordPress HTML. The watch page embeds
 * the site's own "MegaMax" player, a Laravel + Inertia single page application
 * that exposes the real mirror list only when asked with the Inertia headers.
 * Those mirrors (StreamWish, MixDrop, DoodStream, Voe, …) are then handed to
 * CloudStream's extractor registry.
 */
class EgyDead : MainAPI() {
    override var mainUrl = "https://k8w4e.sbs"
    override var name = "EgyDead"
    override var lang = "ar"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override val mainPage = mainPageOf(
        "latest" to "أحدث الإضافات",
        "movies" to "أفلام",
        "series" to "مسلسلات",
        "anime" to "أنمي",
    )

    private enum class PageKind { MOVIE, EPISODE, SEASON, SERIES, COLLECTION }

    private data class Mirror(
        val quality: String,
        val driver: String,
        val url: String,
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val INERTIA_COMPONENT = "files/mirror/video"

        /** How many mirrors are resolved at once, and in total. */
        private const val MIRROR_BATCH = 4
        private const val MIRROR_LIMIT = 12

        private val INERTIA_PAYLOAD =
            Regex("<script data-page=\"app\" type=\"application/json\">([\\s\\S]*?)</script>")

        private val INERTIA_VERSION = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")

        private val MIRROR_ENTRY =
            Regex("\"driver\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"link\"\\s*:\\s*\"([^\"]*)\"")

        private val STREAM_BLOCK =
            Regex("\"label\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"mirrors\"\\s*:\\s*\\[([\\s\\S]*?)]")

        private val VIDEO_URL =
            Regex("https?://[^\"'\\s<>\\\\]+?\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>\\\\]*)?")

        private val EPISODE_NUMBER = Regex("الحلق[ةه]\\s*(\\d+)")

        private val ANY_NUMBER = Regex("(\\d+)")

        private val ORIGIN = Regex("^(https?://[^/]+)")
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
        val path = when (request.data) {
            "movies" -> "/category/english-movies/"
            "series" -> "/departments/series/"
            "anime" -> "/series-category/anime-series/"
            else -> "/"
        }
        val document = app.get(listUrl(path, page), headers = headers()).document
        val items = parseCards(document, 60)
        val hasNext = document.selectFirst(".pagination a.next") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
    }

    private fun listUrl(path: String, page: Int): String {
        if (page <= 1) return "$mainUrl$path"
        return "$mainUrl${path.trimEnd('/')}/page/$page/"
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", headers = headers()).document
        return parseCards(document, 60)
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers()).document
        return when (kindOf(url, document)) {
            PageKind.COLLECTION -> loadCollection(url, document)
            PageKind.EPISODE -> loadEpisode(url, document)
            PageKind.SEASON -> loadSeason(url, document)
            PageKind.SERIES -> loadSeries(url, document)
            PageKind.MOVIE -> loadMovie(url, document)
        }
    }

    private fun kindOf(url: String, document: Document): PageKind {
        if (url.contains("/film-series/")) return PageKind.COLLECTION

        val ld = document.select("script[type=\"application/ld+json\"]")
            .joinToString("\n") { it.data() }

        return when {
            ld.contains("\"TVEpisode\"") -> PageKind.EPISODE
            ld.contains("\"TVSeason\"") -> PageKind.SEASON
            ld.contains("\"TVSeries\"") -> PageKind.SERIES
            ld.contains("\"Movie\"") -> PageKind.MOVIE
            url.contains("/episodes/") -> PageKind.EPISODE
            url.contains("/seasons/") -> PageKind.SEASON
            url.contains("/Series/") -> PageKind.SERIES
            else -> PageKind.MOVIE
        }
    }

    /** An episode page belongs to a season; show that whole season instead. */
    private suspend fun loadEpisode(url: String, document: Document): LoadResponse? {
        val seasonUrl = document.selectFirst("#breadcrumbs a[href*=\"/seasons/\"]")
            ?.attr("href")?.trim()?.takeIf { it.isNotBlank() && it != url }

        if (seasonUrl != null) {
            val seasonDocument = runCatching {
                app.get(seasonUrl, headers = headers(url)).document
            }.getOrNull()
            if (seasonDocument != null) return loadSeason(seasonUrl, seasonDocument)
        }

        val title = document.titleText() ?: return null
        val episode = newEpisode(url) {
            this.name = title
            this.episode = episodeNumber(title)
        }
        return buildShow(url, document, listOf(episode), null)
    }

    private suspend fun loadSeason(url: String, document: Document): LoadResponse? {
        val episodes = parseEpisodes(document)
        if (episodes.isEmpty()) return loadMovie(url, document)
        return buildShow(url, document, episodes, null)
    }

    /** A /Series/ page groups every season of a show – merge them into one entry. */
    private suspend fun loadSeries(url: String, document: Document): LoadResponse? {
        val seasonUrls = document.select("a[href*=\"/seasons/\"]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") && it.contains("/seasons/") }
            .distinct()
            .take(12)

        if (seasonUrls.isEmpty()) return loadMovie(url, document)

        val seasonDocuments = seasonUrls.amap { seasonUrl ->
            runCatching { app.get(seasonUrl, headers = headers(url)).document }.getOrNull()
        }

        val merged = mutableListOf<Episode>()
        seasonDocuments.forEachIndexed { index, seasonDocument ->
            if (seasonDocument == null) return@forEachIndexed
            val seasonNumber = index + 1
            parseEpisodes(seasonDocument).forEach { episode ->
                merged.add(
                    newEpisode(episode.data) {
                        this.name = episode.name
                        this.episode = episode.episode
                        this.season = seasonNumber
                    }
                )
            }
        }

        if (merged.isEmpty()) return loadMovie(url, document)
        return buildShow(url, document, merged, null)
    }

    /** A /film-series/ page is a collection of movie pages. */
    private suspend fun loadCollection(url: String, document: Document): LoadResponse? {
        val episodes = document.select(".salery-list li.movieItem a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = anchor.selectFirst(".BottomTitle")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newEpisode(href) { this.name = title }
        }

        if (episodes.isEmpty()) return loadMovie(url, document)
        return buildShow(url, document, episodes, null)
    }

    private suspend fun loadMovie(url: String, document: Document): LoadResponse? {
        val title = document.titleText() ?: return null
        val info = document.infoRows()
        val type = if (isAnime(document, title)) TvType.AnimeMovie else TvType.Movie

        return newMovieLoadResponse(title, url, type, url.trimEnd('/') + "/watch/") {
            this.posterUrl = document.posterText()
            this.plot = document.plotText()
            this.year = yearOf(info)
            this.tags = info["النوع"].orEmpty()
        }
    }

    private suspend fun buildShow(
        url: String,
        document: Document,
        episodes: List<Episode>,
        overrideTitle: String?,
    ): LoadResponse? {
        val title = overrideTitle ?: document.titleText() ?: return null
        val info = document.infoRows()
        val poster = document.posterText()
        val plot = document.plotText()
        val year = yearOf(info)
        val tags = info["النوع"].orEmpty()

        return if (isAnime(document, title)) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.episodes = mutableMapOf(dubStatus(title) to episodes)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // ------------------------------------------------------------ load links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val watchUrl = if (data.contains("/watch/")) data else "${data.trimEnd('/')}/watch/"

        val document = runCatching {
            app.get(watchUrl, headers = headers(data)).document
        }.getOrNull() ?: return false

        val servers = document.select("ul.serversList li[data-link]").mapNotNull { element ->
            val link = element.attr("data-link").trim()
            if (link.isBlank()) null else element.text().trim() to link
        }
        if (servers.isEmpty()) return false

        var found = false
        val emitted = HashSet<String>()
        val emit: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url)) {
                found = true
                callback(link)
            }
        }

        for ((serverName, serverUrl) in servers) {
            // Players with a bundled extractor resolve in one step.
            val direct = runCatching {
                loadExtractor(serverUrl, watchUrl, subtitleCallback) { link -> emit(link) }
            }.getOrDefault(false)
            if (direct) continue

            val mirrors = runCatching { resolvePlayer(serverUrl, watchUrl) }.getOrNull().orEmpty()

            if (mirrors.isEmpty()) {
                runCatching { genericLinks(serverUrl, serverName, watchUrl, emit) }
                continue
            }

            for (batch in selectMirrors(mirrors).chunked(MIRROR_BATCH)) {
                batch.amap { mirror ->
                    val handled = runCatching {
                        loadExtractor(mirror.url, serverUrl, subtitleCallback) { link ->
                            applyQuality(link, mirror.quality)
                            emit(link)
                        }
                    }.getOrDefault(false)

                    if (!handled) {
                        runCatching {
                            genericLinks(mirror.url, mirror.driver, serverUrl) { link ->
                                applyQuality(link, mirror.quality)
                                emit(link)
                            }
                        }
                    }
                    Unit
                }
            }
        }

        return found
    }

    /** Best quality first, one batch per host so we never hammer a single host. */
    private fun selectMirrors(mirrors: List<Mirror>): List<Mirror> {
        val byQuality = mirrors.groupBy { qualityRank(it.quality) }
            .toSortedMap(compareByDescending { it })

        val selected = mutableListOf<Mirror>()
        val used = HashSet<Mirror>()

        for ((_, group) in byQuality) {
            val perDriver = HashSet<String>()
            for (mirror in group) {
                if (!perDriver.add(mirror.driver)) continue
                if (!used.add(mirror)) continue
                selected.add(mirror)
                if (selected.size >= MIRROR_LIMIT) return selected
            }
        }
        return selected
    }

    /**
     * MegaMax style player: an Inertia app that only returns its mirror list
     * when the request carries the matching asset version.
     */
    private suspend fun resolvePlayer(url: String, referer: String): List<Mirror> {
        val response = app.get(url, headers = headers(referer), timeout = 25)
        if (response.code != 200) return emptyList()

        val payload = INERTIA_PAYLOAD.find(response.text)?.groupValues?.get(1)
            ?: return emptyList()
        // The component name is JSON encoded, so "files/mirror/video" arrives as "files\/mirror\/video".
        if (!payload.replace("\\/", "/").contains(INERTIA_COMPONENT)) return emptyList()

        val version = INERTIA_VERSION.find(payload)?.groupValues?.get(1).orEmpty()
        val json = app.get(
            url,
            headers = headers(referer) + mapOf(
                "X-Inertia" to "true",
                "X-Inertia-Version" to version,
                "X-Inertia-Partial-Data" to "streams",
                "X-Inertia-Partial-Component" to INERTIA_COMPONENT,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "text/html, application/xhtml+xml",
            ),
            timeout = 25,
        ).text

        return parseMirrors(json, url)
    }

    private fun parseMirrors(json: String, baseUrl: String): List<Mirror> {
        if (!json.contains("\"mirrors\"")) return emptyList()
        val origin = ORIGIN.find(baseUrl)?.groupValues?.get(1).orEmpty()

        val mirrors = mutableListOf<Mirror>()
        for (block in STREAM_BLOCK.findAll(json)) {
            val quality = block.groupValues[1].trim()
            for (entry in MIRROR_ENTRY.findAll(block.groupValues[2])) {
                val driver = entry.groupValues[1].trim()
                var link = entry.groupValues[2].replace("\\/", "/").trim()
                when {
                    link.startsWith("//") -> link = "https:$link"
                    link.startsWith("/") && origin.isNotBlank() -> link = origin + link
                }
                if (!link.startsWith("http")) continue
                mirrors.add(Mirror(quality, driver, link))
            }
        }
        return mirrors
    }

    /** Last resort for players no extractor knows: look for a raw m3u8/mp4. */
    private suspend fun genericLinks(
        url: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(url, headers = headers(referer), timeout = 20).text
        }.getOrNull() ?: return false

        var emitted = false
        VIDEO_URL.findAll(html)
            .map { it.value.replace("\\/", "/").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { candidate ->
                val type = if (candidate.contains(".m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} | $serverName",
                        url = candidate,
                        type = type,
                    ) {
                        this.referer = url
                    }
                )
                emitted = true
            }
        return emitted
    }

    private fun applyQuality(link: ExtractorLink, label: String) {
        if (link.quality != Qualities.Unknown.value) return
        val quality = qualityOf(label)
        if (quality != Qualities.Unknown.value) link.quality = quality
    }

    private fun qualityOf(label: String): Int =
        when (qualityRank(label)) {
            2160 -> Qualities.P2160.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

    private fun qualityRank(label: String): Int {
        val number = ANY_NUMBER.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return when {
            number >= 2160 -> 2160
            number >= 1080 -> 1080
            number >= 720 -> 720
            number >= 480 -> 480
            number >= 360 -> 360
            else -> 0
        }
    }

    // --------------------------------------------------------------- parsing

    private fun parseCards(document: Document, limit: Int): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        // Search results, the paginated home section and category archives all
        // expose their cards in a `ul.posts-list`; every other `li.movieItem`
        // on those pages is a pinned/sidebar/related duplicate.
        val scope = document.selectFirst(".catHolder ul.posts-list")
            ?: document.selectFirst("section.main-section ul.posts-list")
            ?: document.selectFirst("ul.posts-list")
            ?: document
        val cards = scope.select("li.movieItem")

        for (card in cards) {
            val anchor = card.selectFirst("a[href]") ?: continue
            val href = anchor.attr("href").trim()
            if (href.isBlank() || !href.startsWith("http")) continue
            if (results.containsKey(href)) continue

            val title = card.selectFirst(".BottomTitle")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: continue

            val image = card.selectFirst("img")
            val poster = image?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-src")?.trim()?.takeIf { it.isNotBlank() }
            val category = card.selectFirst(".cat_name")?.text()?.trim().orEmpty()

            results[href] = searchResponse(title, href, poster, category)
            if (results.size >= limit) break
        }

        return results.values.toList()
    }

    private fun searchResponse(
        title: String,
        url: String,
        poster: String?,
        category: String,
    ): SearchResponse {
        val anime = category.contains("انمي")
        val isShow = url.contains("/episodes/") ||
            url.contains("/seasons/") ||
            url.contains("/Series/") ||
            url.contains("/film-series/")

        return when {
            isShow && anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }

            isShow -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }

            anime -> newMovieSearchResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = poster
            }

            else -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val episodes = LinkedHashMap<Int, Episode>()

        document.select(".EpsList li a[href*=\"/episodes/\"]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach

            val title = anchor.attr("title").trim()
            val number = episodeNumber(title)
                ?: episodeNumber(anchor.text())
                ?: (episodes.size + 1)

            if (episodes.containsKey(number)) return@forEach
            episodes[number] = newEpisode(href) {
                this.name = title.takeIf { it.isNotBlank() } ?: "الحلقة $number"
                this.episode = number
            }
        }

        return episodes.values.sortedBy { it.episode ?: 0 }
    }

    private fun episodeNumber(text: String): Int? {
        EPISODE_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        ANY_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun Document.titleText(): String? =
        selectFirst(".singleTitle em")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

    private fun Document.posterText(): String? =
        selectFirst(".single-thumbnail img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

    private fun Document.plotText(): String? =
        selectFirst(".singleStory")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

    private fun Document.infoRows(): Map<String, List<String>> {
        val info = LinkedHashMap<String, List<String>>()
        select(".LeftBox > ul > li").forEach { row ->
            val label = row.selectFirst("span")?.text()?.trim()
                ?.trimEnd(':', '：')?.trim()
                ?: return@forEach
            val values = row.select("a").map { it.text().trim() }.filter { it.isNotBlank() }
            if (label.isNotBlank() && values.isNotEmpty()) info[label] = values
        }
        return info
    }

    private fun yearOf(info: Map<String, List<String>>): Int? =
        info["السنه"]?.firstOrNull()?.let { ANY_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun isAnime(document: Document, title: String): Boolean {
        if (title.contains("انمي")) return true
        val category = document.infoRows()["القسم"]?.firstOrNull().orEmpty()
        return category.contains("انمي")
    }

    private fun dubStatus(title: String): DubStatus =
        if (title.contains("مدبلج")) DubStatus.Dubbed else DubStatus.Subbed
}
