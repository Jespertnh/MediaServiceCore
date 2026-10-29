package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.rx.RxHelper
import io.reactivex.disposables.Disposable

/**
 * Remembers which videos are labeled by YouTube as AI-generated ("Made with AI", altered or synthetic content).<br/>
 * The label is only available on the watch page, so feeds are filtered with the help of this cache.<br/>
 * NOTE: negative results expire because the label could be added later by the creator or YouTube.
 */
object AiLabelCache {
    private const val DATA_KEY = "ai_label_cache"
    private const val MAX_SIZE = 10_000
    private const val NEGATIVE_TTL_MS = 3L * 24 * 60 * 60 * 1_000
    private const val PERSIST_DELAY_MS = 10_000L
    private const val ITEM_DELIM = "\n"
    private const val FIELD_DELIM = ","
    private val entries by lazy { restoreData() }
    private var persistAction: Disposable? = null

    private data class Entry(val isLabeled: Boolean, val checkedAtMs: Long)

    /**
     * @return null if the video wasn't checked yet or the result is outdated
     */
    @JvmStatic
    fun get(videoId: String?): Boolean? {
        if (videoId == null) {
            return null
        }

        return synchronized(entries) {
            val entry = entries[videoId] ?: return null

            if (!entry.isLabeled && System.currentTimeMillis() - entry.checkedAtMs > NEGATIVE_TTL_MS) {
                entries.remove(videoId)
                return null
            }

            entry.isLabeled
        }
    }

    @JvmStatic
    fun isLabeled(videoId: String?): Boolean = get(videoId) == true

    @JvmStatic
    fun put(videoId: String?, isLabeled: Boolean) {
        if (videoId == null) {
            return
        }

        synchronized(entries) {
            // Positive results never expire, no need to update
            if (isLabeled && entries[videoId]?.isLabeled == true) {
                return
            }

            entries[videoId] = Entry(isLabeled, System.currentTimeMillis())
        }

        persistData()
    }

    private fun createMap() = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_SIZE
    }

    private fun restoreData(): LinkedHashMap<String, Entry> {
        val result = createMap()

        if (Helpers.isJUnitTest()) {
            return result
        }

        MediaServicePrefs.getData(DATA_KEY)?.split(ITEM_DELIM)?.forEach {
            val fields = it.split(FIELD_DELIM)
            val checkedAtMs = fields.getOrNull(2)?.toLongOrNull() ?: return@forEach
            result[fields[0]] = Entry(fields[1] == "1", checkedAtMs)
        }

        return result
    }

    @Synchronized
    private fun persistData() {
        if (Helpers.isJUnitTest()) {
            return
        }

        RxHelper.disposeActions(persistAction)

        // Merge multiple persist requests (e.g. when the whole feed is being checked)
        persistAction = RxHelper.runAsync({ persistDataInt() }, PERSIST_DELAY_MS)
    }

    private fun persistDataInt() {
        val data = synchronized(entries) {
            entries.entries.joinToString(ITEM_DELIM) {
                "${it.key}$FIELD_DELIM${if (it.value.isLabeled) 1 else 0}$FIELD_DELIM${it.value.checkedAtMs}"
            }
        }

        MediaServicePrefs.setData(DATA_KEY, data)
    }
}
