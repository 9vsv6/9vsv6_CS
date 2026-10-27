package com.arabicmulti

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * EgyDead (ايجي ديد) link source.
 *
 * Finds a title/year on the site, walks to the matching season + episode and
 * resolves the watch page's "MegaMax" player, which exposes the real mirror list
 * through Inertia's partial-reload headers.
 */
object EgyDeadSource {
    private const val BASE = "https://k8w4e.sbs"
    private const val NAME = "EgyDead"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val INERTIA_COMPONENT = "files/mirror/video"
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

    private data class Card(val title: String, val url: String)
    private data class Mirror(val quality: String, val driver: String, val url: String)

    private fun headers(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        put("sec-ch-ua", "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not?A_Brand\";v=\"24\"")
        put("sec-ch-ua-mobile", "?0")
        put("sec-ch-ua-platform", "\"Windows\"")
        put("Sec-Fetch-Dest", "document")
        put("Sec-Fetch-Mode", "navigate")
        put("Sec-Fetch-Site", if (referer == null) "none" else "same-origin")
        put("Sec-Fetch-User", "?1")
        put("Upgrade-Insecure-Requests", "1")
        if (referer != null) put("Referer", referer)
    }

    private fun playerHeaders(referer: String): Map<String, String> = headers(referer).toMutableMap().apply {
        this["Sec-Fetch-Dest"] = "iframe"
    }

    suspend fun resolve(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val cards = try {
            search(title)
        } catch (_: Exception) {
            return false
        }
        Log.d("ArabicMulti", "EgyDead search '$title' -> ${cards.size} cards")
        if (cards.isEmpty()) return false

        val wanted = normalise(title)
        val ranked = cards
            .map { card -> card to titleScore(wanted, normalise(card.title)) }
            .filter { it.second <= 2 }
            .sortedWith(
                compareBy(
                    { it.second },
                    { if (year != null && it.first.title.contains(year.toString())) 0 else 1 },
                )
            )
            .take(6)

        Log.d("ArabicMulti", "EgyDead ranked=${ranked.size} best=${ranked.firstOrNull()?.second} title=${ranked.firstOrNull()?.first?.title}")
        for ((card, _) in ranked) {
            try {
                if (season == null || episode == null) {
                    if (loadWatch("${card.url.trimEnd('/')}/watch/", subtitleCallback, callback)) return true
                    continue
                }

                val page = app.get(card.url, headers = headers()).document
                val episodeUrl = findEpisodeUrl(page, season, episode) ?: continue
                if (loadWatch("${episodeUrl.trimEnd('/')}/watch/", subtitleCallback, callback)) return true
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        return false
    }

    // ------------------------------------------------------------- discovery

    private suspend fun search(title: String): List<Card> {
        val query = URLEncoder.encode(title, "UTF-8")
        val document = app.get("$BASE/?s=$query", headers = headers()).document
        val scope = document.selectFirst(".catHolder ul.posts-list")
            ?: document.selectFirst("section.main-section ul.posts-list")
            ?: document.selectFirst("ul.posts-list")
            ?: document

        val cards = LinkedHashMap<String, Card>()
        for (card in scope.select("li.movieItem")) {
            val anchor = card.selectFirst("a[href]") ?: continue
            val href = anchor.attr("href").trim()
            if (!href.startsWith("http") || cards.containsKey(href)) continue
            val name = card.selectFirst(".BottomTitle")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: continue
            cards[href] = Card(name, href)
        }
        return cards.values.toList()
    }

    private suspend fun findEpisodeUrl(page: Document, season: Int, episode: Int): String? {
        val direct = parseEpisodes(page)
        if (direct.isNotEmpty()) {
            // A single-season page: the search hit is already the right season.
            direct.firstOrNull { it.first == episode }?.let { return it.second }
            if (season <= 1) return null
        }

        val seasonUrls = page.select("a[href*=\"/seasons/\"]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .take(15)

        for ((index, seasonUrl) in seasonUrls.withIndex()) {
            val seasonDocument = try {
                app.get(seasonUrl, headers = headers(page.baseUri())).document
            } catch (_: Exception) {
                continue
            }
            val episodes = parseEpisodes(seasonDocument)
            if (episodes.isEmpty()) continue
            if (episodes.none { it.first == episode }) continue

            val number = seasonOf(seasonDocument) ?: (index + 1)
            if (number != season) continue
            return episodes.first { it.first == episode }.second
        }
        return null
    }

    private fun parseEpisodes(document: Document): List<Pair<Int, String>> {
        val episodes = LinkedHashMap<Int, String>()
        document.select(".EpsList li a[href*=\"/episodes/\"]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach
            val number = episodeNumber(anchor.attr("title"))
                ?: episodeNumber(anchor.text())
                ?: (episodes.size + 1)
            episodes.putIfAbsent(number, href)
        }
        return episodes.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun episodeNumber(text: String): Int? {
        EPISODE_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        ANY_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private val ARABIC_SEASONS = mapOf(
        "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
        "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
        "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12,
    )

    private fun seasonOf(document: Document): Int? {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() +
            " " + document.title()
        Regex("""الموسم\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)season\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        for ((word, number) in ARABIC_SEASONS) {
            if (title.contains(word)) return number
        }
        return null
    }

    private fun titleScore(wanted: String, candidate: String): Int {
        if (wanted.isBlank() || candidate.isBlank()) return Int.MAX_VALUE
        return when {
            candidate == wanted -> 0
            candidate.startsWith(wanted) || wanted.startsWith(candidate) -> 1
            candidate.contains(wanted) || wanted.contains(candidate) -> 2
            else -> Int.MAX_VALUE
        }
    }

    private fun normalise(value: String): String =
        value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            // No \b here: Arabic letters are not \w, so \b(فيلم)\b never matches.
            .replace(Regex("""(مشاهدة|تحميل|مترجم|مترجمة|اون|لاين|كامل|مسلسل|فيلم)"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---------------------------------------------------------------- player

    private fun extractServers(document: Document): List<Pair<String, String>> {
        val primary = document.select("ul.serversList li[data-link]").mapNotNull { element ->
            val link = element.attr("data-link").trim()
            if (link.isBlank()) null else element.text().trim().ifBlank { "server" } to link
        }
        if (primary.isNotEmpty()) return primary

        // Interstitial / alternate layouts: any data-link, then a bare iframe.
        val anyDataLink = document.select("[data-link]").mapNotNull { element ->
            val link = element.attr("data-link").trim()
            if (!link.startsWith("http")) null else element.text().trim().ifBlank { "server" } to link
        }
        if (anyDataLink.isNotEmpty()) return anyDataLink

        val iframe = document.selectFirst("iframe[src]")?.attr("src")?.trim()
        if (!iframe.isNullOrBlank() && iframe.startsWith("http")) {
            return listOf("iframe" to iframe)
        }
        return emptyList()
    }

    private suspend fun loadWatch(
        watchUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val first = try {
            app.get(watchUrl, headers = headers(watchUrl), timeout = 30)
        } catch (_: Exception) {
            return false
        }

        var servers = extractServers(first.document)

        // The site sometimes replies with a short "wait a moment" interstitial
        // that only sets a cookie; retry once with those cookies.
        if (servers.isEmpty() && first.cookies.isNotEmpty()) {
            val retry = runCatching {
                app.get(watchUrl, headers = headers(watchUrl), cookies = first.cookies, timeout = 30)
            }.getOrNull()
            if (retry != null) servers = extractServers(retry.document)
        }

        Log.d("ArabicMulti", "EgyDead watch servers=${servers.size}")
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
            val direct = runCatching {
                loadExtractor(serverUrl, watchUrl, subtitleCallback) { link -> emit(link) }
            }.getOrDefault(false)
            if (direct) continue

            val mirrors = runCatching { resolvePlayer(serverUrl, watchUrl) }.getOrNull().orEmpty()
            Log.d("ArabicMulti", "EgyDead server $serverName mirrors=${mirrors.size}")
            if (mirrors.isEmpty()) {
                runCatching { genericLinks(serverUrl, serverName, watchUrl, emit) }
                continue
            }

            for (batch in selectMirrors(mirrors).chunked(MIRROR_BATCH)) {
                for (mirror in batch) {
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
                }
            }
        }
        return found
    }

    private fun selectMirrors(mirrors: List<Mirror>): List<Mirror> {
        val byQuality = mirrors.groupBy { qualityRank(it.quality) }.toSortedMap(compareByDescending { it })
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

    private suspend fun resolvePlayer(url: String, referer: String): List<Mirror> {
        val response = app.get(url, headers = playerHeaders(referer), timeout = 25)
        if (response.code != 200) return emptyList()

        var payload = INERTIA_PAYLOAD.find(response.text)?.groupValues?.get(1)
        if (payload == null && response.cookies.isNotEmpty()) {
            val retry = runCatching {
                app.get(url, headers = playerHeaders(referer), cookies = response.cookies, timeout = 25)
            }.getOrNull()
            if (retry != null) payload = INERTIA_PAYLOAD.find(retry.text)?.groupValues?.get(1)
        }
        payload ?: return emptyList()
        // The component name is JSON encoded, so "files/mirror/video" arrives as "files\/mirror\/video".
        if (!payload.replace("\\/", "/").contains(INERTIA_COMPONENT)) return emptyList()

        val version = INERTIA_VERSION.find(payload)?.groupValues?.get(1).orEmpty()
        val json = app.get(
            url,
            headers = playerHeaders(referer) + mapOf(
                "X-Inertia" to "true",
                "X-Inertia-Version" to version,
                "X-Inertia-Partial-Data" to "streams",
                "X-Inertia-Partial-Component" to INERTIA_COMPONENT,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "text/html, application/xhtml+xml",
            ),
            cookies = response.cookies,
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
                callback(
                    newExtractorLink(
                        source = NAME,
                        name = "$NAME | $serverName",
                        url = candidate,
                        type = if (candidate.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
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

    private fun qualityOf(label: String): Int = when (qualityRank(label)) {
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
}
