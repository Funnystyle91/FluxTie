package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.api.models.protos.threadList.ThreadListResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.utils.appPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

object FrsPageRepository {
    var lastHash: String = ""
    var lastResponse: FrsPageResponse? = null

    /** 进吧预取的独立协程作用域（IO），与页面生命周期解耦，点到即发、即弃。 */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 进吧预取（方案 C：点击即发，与导航转场并行）。
     * 在用户“点下吧”的瞬间提前发起首屏 frsPage 请求，采用与 ForumViewModel.Load 完全相同的
     * hash（含每吧独立 sortType），使 ForumPage 组合完成后首页数据大概率已就绪或在途，
     * 从而消除“整屏白等”。请求结果经 frsPage 的 onEach 写入 lastResponse，供页面命中复用。
     */
    fun prefetch(forumName: String) {
        prefetchScope.launch {
            runCatching {
                val defaultSortType =
                    App.INSTANCE.appPreferences.defaultSortType?.toIntOrNull() ?: 0
                val sortType =
                    App.INSTANCE.dataStore.getInt("${forumName}_sort_type", defaultSortType)
                frsPage(forumName, 1, 1, sortType, null, true).collect {}
            }
        }
    }

    fun frsPage(
        forumName: String,
        page: Int,
        loadType: Int,
        sortType: Int,
        goodClassifyId: Int? = null,
        forceNew: Boolean = false,
    ): Flow<FrsPageResponse> {
        val hash = "${forumName}_${page}_${loadType}_${sortType}_${goodClassifyId}"
        if (!forceNew && lastResponse != null && lastHash == hash) {
            return flowOf(lastResponse!!)
        }
        lastHash = hash
        return TiebaApi.getInstance().frsPage(forumName, page, loadType, sortType, goodClassifyId)
            .map { response ->
                if (response.data_ == null) throw TiebaUnknownException
                val userList = response.data_.user_list
                val threadList = response.data_.thread_list
                    .map { threadInfo ->
                        threadInfo.copy(author = userList.find { it.id == threadInfo.authorId })
                    }
                    .filter { !App.INSTANCE.appPreferences.blockVideo || it.videoInfo == null }
                    .filter { it.ala_info == null } // 去他妈的直播
                response.copy(data_ = response.data_.copy(thread_list = threadList))
            }
            .onEach { lastResponse = it }
    }

    fun threadList(
        forumId: Long,
        forumName: String,
        page: Int,
        sortType: Int,
        threadIds: String = "",
    ): Flow<ThreadListResponse> =
        TiebaApi.getInstance()
            .threadList(forumId, forumName, page, sortType, threadIds)
            .map { response ->
                if (response.data_ == null) throw TiebaUnknownException
                val userList = response.data_.user_list
                val threadList = response.data_.thread_list
                    .map { threadInfo ->
                        threadInfo.copy(author = userList.find { it.id == threadInfo.authorId })
                    }
                    .filter { !App.INSTANCE.appPreferences.blockVideo || it.videoInfo == null }
                    .filter { it.ala_info == null } // 去他妈的直播
                response.copy(data_ = response.data_.copy(thread_list = threadList))
            }
}