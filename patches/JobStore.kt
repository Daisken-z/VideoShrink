package com.example.videoshrink

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the active batch only for service/process safety. The UI deliberately does not restore
 * a completed batch on a normal cold launch; completed output files already live in Movies/VideoShrink.
 */
object JobStore {
    private const val PREFS = "video_shrink_jobs"
    private const val KEY_ITEMS = "items"

    fun save(context: Context, items: List<VideoItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("uri", item.uri.toString())
                put("displayName", item.displayName)
                put("originalSize", item.originalSize)
                put("width", item.width)
                put("height", item.height)
                put("durationMs", item.durationMs)
                put("selected", item.selected)
                put("status", item.status.name)
                put("progress", item.progress)
                put("outputUri", item.outputUri?.toString() ?: "")
                put("outputSize", item.outputSize)
                put("error", item.error ?: "")
                put("originalDeleted", item.originalDeleted)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }

    fun load(context: Context): MutableList<VideoItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val obj = array.getJSONObject(index)
                val storedStatus = runCatching { VideoStatus.valueOf(obj.getString("status")) }
                    .getOrDefault(VideoStatus.READY)
                val restoredStatus = if (storedStatus == VideoStatus.COMPRESSING) VideoStatus.CANCELLED else storedStatus
                VideoItem(
                    uri = Uri.parse(obj.getString("uri")),
                    displayName = obj.getString("displayName"),
                    originalSize = obj.optLong("originalSize", 0L),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    durationMs = obj.optLong("durationMs", 0L),
                    selected = obj.optBoolean("selected", true),
                    status = restoredStatus,
                    progress = if (restoredStatus == VideoStatus.CANCELLED) 0 else obj.optInt("progress", 0),
                    outputUri = obj.optString("outputUri").takeIf { it.isNotBlank() }?.let(Uri::parse),
                    outputSize = obj.optLong("outputSize", 0L),
                    error = obj.optString("error").takeIf { it.isNotBlank() },
                    originalDeleted = obj.optBoolean("originalDeleted", false),
                )
            }
        }.getOrElse { mutableListOf() }
    }
}
