package com.arabicmulti

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Arabic Multi – TMDB powered catalogue (movies + series, no anime) whose
 * playback is resolved from Arabic streaming sites.
 *
 * The catalogue, posters, plots, cast and episode list all come from TMDB.
 * [loadLinks] receives a [TmdbLink] (title + season + episode) and asks every
 * configured source for the same title; sources are independent so adding a new
 * site only means writing one more resolver.
 */
class ArabicMulti : TmdbProvider() {
    override var name = "Arabic Multi"
    override var mainUrl = "https://www.themoviedb.org"
    override val apiName = "ArabicMulti"
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = tryParseJson<TmdbLink>(data) ?: return false
        val title = link.movieName?.takeIf { it.isNotBlank() } ?: return false
        val season = link.season
        val episode = link.episode

        var found = false

        // Source 1: EgyDead (movies, series; resolves MegaMax mirrors).
        if (runCatching {
                EgyDeadSource.resolve(title, null, season, episode, subtitleCallback, callback)
            }.getOrDefault(false)
        ) {
            found = true
        }

        return found
    }
}
