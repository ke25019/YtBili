package com.ytstyle.bili.data.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.ytstyle.bili.data.net.RecommendItem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last recommendation page, kept on disk so a launch can show it without a
 * request.
 *
 * Only the "打开应用时自动刷新推荐流" switch needs this: when it is off, the
 * feed should come back exactly as the user left it instead of being refetched.
 * A cache miss falls back to a normal load, so a first launch still works.
 */
@Singleton
class HomeFeedCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val serializer = ListSerializer(RecommendItem.serializer())

    private val file: File get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): List<RecommendItem> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            json.decodeFromString(serializer, file.readText())
        }.getOrElse { emptyList() }
    }

    suspend fun save(items: List<RecommendItem>) = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(serializer, items.take(MAX_ITEMS)))
        }
        Unit
    }

    private companion object {
        const val FILE_NAME = "home_feed.json"

        /** Enough to fill several screens; the file stays small. */
        const val MAX_ITEMS = 30
    }
}
