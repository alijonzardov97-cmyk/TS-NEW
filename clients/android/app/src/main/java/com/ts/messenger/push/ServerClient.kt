package com.ts.messenger.push

import android.content.Context
import com.ts.messenger.net.AppJson
import com.ts.messenger.net.HttpClientFactory
import com.ts.messenger.net.Session
import com.ts.messenger.net.TsApi
import com.ts.messenger.security.SecureStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds an API client from saved settings, for code that runs outside the UI (push service). */
object ServerClient {
    fun fromStore(context: Context): TsApi? {
        val store = SecureStore(context)
        val url = store.getString("server.url")?.toHttpUrlOrNull() ?: return null
        val pins = store.getString("server.pins")
            ?.let { runCatching { AppJson.decodeFromString<List<String>>(it) }.getOrNull() }
            .orEmpty()
        if (pins.isEmpty()) return null
        val api = TsApi(url, HttpClientFactory.create(url.host, pins))
        api.session = Session(store) { api.refresh(it) }
        return api
    }
}
