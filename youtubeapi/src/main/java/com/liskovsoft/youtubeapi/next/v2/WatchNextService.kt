package com.liskovsoft.youtubeapi.next.v2

import com.liskovsoft.mediaserviceinterfaces.data.DislikeData
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.browse.v1.BrowseApiHelper
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.googlecommon.common.helpers.YouTubeHelper
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.MediaGroupOptions
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.SuggestionsGroup
import com.liskovsoft.youtubeapi.next.v2.gen.DislikesResult
import com.liskovsoft.youtubeapi.next.v2.gen.UnlocalizedTitleResult
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResult
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.gen.getDescriptionPanel
import com.liskovsoft.youtubeapi.next.v2.gen.getDislikeCount
import com.liskovsoft.youtubeapi.next.v2.gen.getLikeCount
import com.liskovsoft.youtubeapi.next.v2.gen.isAiLabeled
import com.liskovsoft.youtubeapi.next.v2.gen.isEmpty
import com.liskovsoft.youtubeapi.next.v2.impl.MediaItemMetadataImpl
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.internal.AiLabelCache
import java.util.concurrent.Semaphore

internal open class WatchNextService {
    private var mWatchNextApi = RetrofitHelper.create(WatchNextApi::class.java)
    private val mAppService = AppService.instance()
    // Limit background AI label checks to not flood the network
    private val mAiLabelPermits = Semaphore(3)

    fun getMetadata(videoId: String): MediaItemMetadata? {
        return getMetadata(videoId, null, 0)
    }

    fun getMetadata(item: MediaItem): MediaItemMetadata? {
        return getMetadata(item.videoId, item.playlistId, item.playlistIndex)
    }

    open fun getMetadata(videoId: String?, playlistId: String?, playlistIndex: Int): MediaItemMetadata? {
        return getMetadata(videoId, playlistId, playlistIndex, null)
    }

    open fun getMetadata(videoId: String?, playlistId: String?, playlistIndex: Int, playlistParams: String?): MediaItemMetadata? {
        val watchNext = getWatchNext(videoId, playlistId, playlistIndex, playlistParams) ?: return null

        if (videoId == null && watchNext.isEmpty()) {
            return null
        }

        // Description is missing on some pages (e.g. mixes). Don't treat it as "not labeled".
        watchNext.getDescriptionPanel()?.let { AiLabelCache.put(videoId, watchNext.isAiLabeled()) }

        return MediaItemMetadataImpl(watchNext).apply {
            channelId?.let {
                ChannelGroupServiceImpl.cachedChannel = ItemImpl(it, author, authorImageUrl)
                if (!YouTubeSignInService.instance().isSigned) {
                    isSubscribedOverrideItem = ChannelGroupServiceImpl.isSubscribed(it)
                } else if (isSubscribed != ChannelGroupServiceImpl.isSubscribed(it)) {
                    ChannelGroupServiceImpl.subscribe(isSubscribed, it, author, authorImageUrl)
                }
            }
        }
    }

    /**
     * Whether the video is labeled by YouTube as AI-generated (altered or synthetic content).<br/>
     * The label is only available on the watch page, so the result is cached.
     * @return null if the check failed
     */
    fun isAiLabeled(videoId: String?): Boolean? {
        if (videoId == null) {
            return null
        }

        AiLabelCache.get(videoId)?.let { return it }

        mAiLabelPermits.acquire()

        try {
            // The same video could be checked by another row in the meantime
            AiLabelCache.get(videoId)?.let { return it }

            // The label is public. Don't use the account to not affect recommendations.
            val watchNext = getWatchNext(WatchNextApiHelper.getWatchNextQuery(
                MediaGroupOptions.create(MediaGroup.TYPE_SUGGESTIONS).clientTV, videoId, null, 0, null), false) ?: return null
            val isLabeled = watchNext.getDescriptionPanel()?.let { watchNext.isAiLabeled() } ?: return null

            AiLabelCache.put(videoId, isLabeled)

            return isLabeled
        } finally {
            mAiLabelPermits.release()
        }
    }

    fun continueGroup(mediaGroup: MediaGroup?): MediaGroup? {
        val nextKey = YouTubeHelper.extractNextKey(mediaGroup) ?: return null

        var continuation = continueWatchNext(BrowseApiHelper.getContinuationQuery(nextKey))

        if (continuation == null || continuation.isEmpty()) {
            continuation = continueWatchNext(BrowseApiHelper.getContinuationQuery(nextKey), false)
        }

        return SuggestionsGroup.from(continuation, mediaGroup)
    }

    fun getDislikeData(videoId: String?): DislikeData? {
        return getDislikesResult(videoId)?.let {
             object : DislikeData {
                 override fun getVideoId(): String? {
                     return it.id
                 }

                 override fun getLikeCount(): String? {
                     return it.getLikeCount()
                 }

                 override fun getDislikeCount(): String? {
                     return it.getDislikeCount()
                 }

                 override fun getViewCount(): Long {
                     return it.viewCount ?: 0
                 }
             }
        }
    }

    fun getUnlocalizedTitle(videoId: String?): String? {
        return getUnlocalizedTitleResult(videoId)?.title
    }

    private fun getWatchNext(videoId: String?, playlistId: String?, playlistIndex: Int, playlistParams: String?): WatchNextResult? {
        return getWatchNext(WatchNextApiHelper.getWatchNextQuery(
            MediaGroupOptions.create(MediaGroup.TYPE_SUGGESTIONS).clientTV, videoId, playlistId, playlistIndex, playlistParams))
    }

    private fun getWatchNext(query: String, auth: Boolean = true): WatchNextResult? {
        val wrapper = mWatchNextApi.getWatchNextResult(query, mAppService.visitorData)

        return RetrofitHelper.get(wrapper, auth)
    }

    private fun continueWatchNext(query: String, auth: Boolean = true): WatchNextResultContinuation? {
        val wrapper = mWatchNextApi.continueWatchNextResult(query, mAppService.visitorData)

        return RetrofitHelper.get(wrapper, auth)
    }

    private fun getDislikesResult(videoId: String?): DislikesResult? {
        if (videoId == null) {
            return null
        }

        val wrapper = mWatchNextApi.getDislikes(videoId)

        return RetrofitHelper.get(wrapper)
    }

    private fun getUnlocalizedTitleResult(videoId: String?): UnlocalizedTitleResult? {
        if (videoId == null) {
            return null
        }

        val wrapper = mWatchNextApi.getUnlocalizedTitle(WatchNextApiHelper.getUnlocalizedTitleQuery(videoId))

        return RetrofitHelper.get(wrapper)
    }

    /**
     * For testing (mocking) purposes only
     */
    fun setWatchNextApi(watchNextApi: WatchNextApi) {
        mWatchNextApi = watchNextApi
    }
}