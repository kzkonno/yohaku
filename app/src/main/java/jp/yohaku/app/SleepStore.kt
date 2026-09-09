package jp.yohaku.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores raw imports and local overrides together so corrections survive process restarts. */
class SleepStore(context: Context) {
    private val prefs = context.getSharedPreferences("yohaku-sleep", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", false)
        set(value) { check(prefs.edit().putBoolean("automatic", value).commit()) }
    var lastSync: Long
        get() = prefs.getLong("lastSync", 0)
        set(value) { check(prefs.edit().putLong("lastSync", value).commit()) }

    /** Throws on corrupt storage rather than silently replacing the user's corrections. */
    fun read(): List<SleepEntry> {
        val array = JSONArray(prefs.getString("entries", "[]"))
        return List(array.length()) { index ->
            val row = array.getJSONObject(index)
            SleepEntry(row.getString("id"), row.getString("title"), row.getLong("start"), row.getLong("end"), row.getString("source"), row.getBoolean("imported"), row.getBoolean("edited"), row.getBoolean("hidden"), row.getLong("originalStart"), row.getLong("originalEnd"))
        }
    }

    /** Commits a single snapshot atomically before the UI reports a successful edit. */
    fun write(entries: List<SleepEntry>) {
        val array = JSONArray()
        entries.forEach { row -> array.put(JSONObject().put("id", row.id).put("title", row.title).put("start", row.start).put("end", row.end).put("source", row.source).put("imported", row.imported).put("edited", row.edited).put("hidden", row.hidden).put("originalStart", row.originalStart).put("originalEnd", row.originalEnd)) }
        check(prefs.edit().putString("entries", array.toString()).commit())
    }
}
