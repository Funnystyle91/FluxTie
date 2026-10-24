package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.api.models.protos.threadList.ThreadListResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.getInt
import com.huanchengfly.tieba.post.utils.appPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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

    /**
     * 磁盘缓存：把整包 frsPage 响应（Wire 序列化）落盘，使进吧时可秒显上次内容，后台再静默刷新。
     * 键由 forumName/page/loadType/sortType/goodClassifyId 组成；同一会话内（最新 tab + 头部）共用同一键。
     */
    private const val CACHE_DIR_NAME = "frs_page"
    private const val CACHE_PREFIX = "frs_"
    private const val CACHE_MAX_FILES = 60

    private fun cacheDir(): File {
        val dir = File(App.INSTANCE.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cacheFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = CACHE_PREFIX + digest.joinToString("") { "%02x".format(it) } + ".pb"
        return File(cacheDir(), name)
    }

    suspend fun readCache(key: String): FrsPageResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(key)
            if (!file.exists() || file.length() == 0L) return@withContext null
            file.inputStream().use { FrsPageResponse.ADAPTER.decode(it) }
        }.getOrNull()
    }

    private fun writeCache(key: String, response: FrsPageResponse) {
        runCatching {
            val file = cacheFile(key)
            file.writeBytes(response.encode())
            // 简单上限：超过阈值时删除最旧文件，避免无限增长
            val dir = cacheDir()
            val files = dir.listFiles { f -> f.name.startsWith(CACHE_PREFIX) } ?: return@runCatching
            if (files.size > CACHE_MAX_FILES) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - CACHE_MAX_FILES)
                    .forEach { it.delete() }
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
        val cacheKey = "$forumName|$page|$loadType|$sortType|$goodClassifyId"
        val network: Flow<FrsPageResponse> =
            TiebaApi.getInstance().frsPage(forumName, page, loadType, sortType, goodClassifyId)
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
                .onEach {
                    lastResponse = it
                    withContext(Dispatchers.IO) { writeCache(cacheKey, it) }
                }
        if (!forceNew) {
            // 先判定内存缓存是否命中（必须在改写 lastHash 之前），内存或磁盘任一命中即秒显旧内容
            val memoryHit = if (lastResponse != null && lastHash == hash) lastResponse else null
            lastHash = hash
            return flow {
                val cached = memoryHit ?: readCache(cacheKey)
                if (cached != null) {
                    emit(cached)
                    // 后台网络刷新；网络失败（含数据异常）静默保留已显示的缓存内容
                    try {
                        emitAll(network)
                    } catch (_: Throwable) {
                    }
                } else {
                    emitAll(network)
                }
            }
        }
        lastHash = hash
        return network
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