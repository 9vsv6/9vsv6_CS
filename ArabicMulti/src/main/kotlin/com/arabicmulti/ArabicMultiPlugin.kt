package com.arabicmulti

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabicMultiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabicMulti())
    }
}
