package com.arabicmulti

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Arabic Multi – TMDB powered catalogue (movies + series, no anime) whose
 * playback is resolved from Arabic streaming sites.
 *
 * TMDB is queried directly instead of extending CloudStream's TmdbProvider:
 * the runtime used by Harbor only ships a stub of that class, so relying on it
 * throws ClassNotFoundException at plugin load time.
 */
class ArabicMulti : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "Arabic Multi"
    override var lang = "ar"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movie_popular" to "أفلام رائجة",
        "tv_popular" to "مسلسلات رائجة",
        "movie_top" to "أفضل الأفلام",
        "tv_top" to "أفضل المسلسلات",
    )

    companion object {
        private const val API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        private const val SITE = "https://www.themoviedb.org"

        private val LETTERS = Regex("""[^\p{L}\p{N}]+""")
    }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "CloudStream-ArabicMulti",
    )

    private fun apiUrl(path: String, params: Map<String, String>): String {
        val builder = StringBuilder("$mainUrl$path?api_key=$API_KEY&language=en-US")
        for ((key, value) in params) {
            builder.append('&').append(key).append('=').append(URLEncoder.encode(value, "UTF-8"))
        }
        return builder.toString()
    }

    private suspend fun getJson(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        return try {
            JSONObject(app.get(apiUrl(path, params), headers = headers(), timeout = 30).text)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (path, type) = when (request.data) {
            "tv_popular" -> "/tv/popular" to "tv"
            "movie_top" -> "/movie/top_rated" to "movie"
            "tv_top" -> "/tv/top_rated" to "tv"
            else -> "/movie/popular" to "movie"
        }

        val json = getJson(path, mapOf("page" to page.toString())) ?: return newHomePageResponse(request.name, emptyList())
        val items = parseList(json.optJSONArray("results"), type)
        val hasNext = page < (json.optInt("total_pages", 1))
        return newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val json = getJson(
            "/search/multi",
            mapOf("query" to query, "page" to "1", "include_adult" to "false"),
        ) ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val type = item.optString("media_type")
            if (type != "movie" && type != "tv") continue
            if (isAnime(item)) continue

            val id = item.optInt("id")
            if (id <= 0) continue
            val title = displayTitle(item, type).takeIf { it.isNotBlank() } ?: continue
            val poster = posterOf(item)
            val year = yearOf(item, type)

            out.add(
                if (type == "movie") {
                    newMovieSearchResponse(title, "$SITE/movie/$id", TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                } else {
                    newTvSeriesSearchResponse(title, "$SITE/tv/$id", TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            )
        }
        return out
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val id = Regex("""/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val type = if (isTv) "tv" else "movie"

        val json = getJson("/$type/$id", mapOf("append_to_response" to "credits")) ?: return null
        val title = displayTitle(json, type).takeIf { it.isNotBlank() } ?: return null
        val poster = posterOf(json)
        val plot = json.optString("overview").takeIf { it.isNotBlank() }
        val year = yearOf(json, type)
        val tags = json.optJSONArray("genres")?.let { genres ->
            (0 until genres.length()).mapNotNull { genres.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }

        return if (isTv) {
            val episodes = loadEpisodes(id, title)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // Movies use 0/0 instead of JSON null: some Android JSON stubs serialise
            // JSONObject.NULL as the string "null", which then parses as season 0.
            val data = JSONObject().apply {
                put("title", title)
                put("season", 0)
                put("episode", 0)
            }.toString()

            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = json.optInt("runtime", 0).takeIf { it > 0 }
            }
        }
    }

    private suspend fun loadEpisodes(id: Int, title: String): List<com.lagradost.cloudstream3.Episode> {
        val details = getJson("/tv/$id") ?: return emptyList()
        val seasons = details.optJSONArray("seasons") ?: return emptyList()

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        for (index in 0 until minOf(seasons.length(), 15)) {
            val season = seasons.optJSONObject(index) ?: continue
            val seasonNumber = season.optInt("season_number", -1)
            if (seasonNumber < 0) continue
            val count = season.optInt("episode_count", 0)
            if (count <= 0) continue
            if (seasonNumber == 0) continue // specials are usually not on these sites

            val seasonJson = getJson("/tv/$id/season/$seasonNumber") ?: continue
            val list = seasonJson.optJSONArray("episodes") ?: continue
            for (episodeIndex in 0 until list.length()) {
                val item = list.optJSONObject(episodeIndex) ?: continue
                val number = item.optInt("episode_number", -1)
                if (number < 0) continue

                val data = JSONObject().apply {
                    put("title", title)
                    put("season", seasonNumber)
                    put("episode", number)
                }.toString()

                episodes.add(
                    newEpisode(data) {
                        this.name = item.optString("name").takeIf { it.isNotBlank() } ?: "الحلقة $number"
                        this.season = seasonNumber
                        this.episode = number
                    }
                )
            }
        }
        return episodes
    }

    // ------------------------------------------------------------- load links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // load() hands over {"title": .., "season": .., "episode": ..} for both
        // movies (nulls) and series episodes.
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            return false
        }

        val title = json.optString("title").takeIf { it.isNotBlank() } ?: return false
        val season = json.optInt("season", 0).takeIf { it > 0 }
        val episode = json.optInt("episode", 0).takeIf { it > 0 }

        var found = false
        if (runCatching {
                EgyDeadSource.resolve(title, null, season, episode, subtitleCallback, callback)
            }.getOrDefault(false)
        ) {
            found = true
        }
        return found
    }

    // --------------------------------------------------------------- helpers

    private fun parseList(results: JSONArray?, type: String): List<SearchResponse> {
        if (results == null) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            if (isAnime(item)) continue
            val id = item.optInt("id")
            if (id <= 0) continue
            val title = displayTitle(item, type).takeIf { it.isNotBlank() } ?: continue
            val poster = posterOf(item)
            val year = yearOf(item, type)

            out.add(
                if (type == "movie") {
                    newMovieSearchResponse(title, "$SITE/movie/$id", TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                } else {
                    newTvSeriesSearchResponse(title, "$SITE/tv/$id", TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            )
        }
        return out
    }

    private fun displayTitle(item: JSONObject, type: String): String =
        if (type == "movie") item.optString("title") else item.optString("name")

    private fun posterOf(item: JSONObject): String? {
        val path = item.optString("poster_path")
        return if (path.isBlank()) null else IMAGE_BASE + path
    }

    private fun yearOf(item: JSONObject, type: String): Int? {
        val date = if (type == "movie") item.optString("release_date") else item.optString("first_air_date")
        return date.take(4).toIntOrNull()
    }

    /** Movies and series aimed at Japanese audiences are anime – keep them out. */
    private fun isAnime(item: JSONObject): Boolean {
        val language = item.optString("original_language")
        if (language != "ja") return false
        val genres = item.optJSONArray("genre_ids") ?: return false
        for (index in 0 until genres.length()) {
            if (genres.optInt(index) == 16) return true
        }
        return false
    }
}
