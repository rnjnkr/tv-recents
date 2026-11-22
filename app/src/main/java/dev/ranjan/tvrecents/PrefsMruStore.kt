package dev.ranjan.tvrecents

import android.content.Context

/** Persists recent apps in the dedicated mru preferences file. */
class PrefsMruStore(context: Context) : MruStore {
    private val prefs = context.getSharedPreferences("mru", Context.MODE_PRIVATE)

    override fun load(): List<String> = MruCodec.decode(prefs.getString("packages", null))

    override fun save(packages: List<String>) {
        prefs.edit().putString("packages", MruCodec.encode(packages)).apply()
    }
}
