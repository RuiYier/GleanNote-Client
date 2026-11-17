package com.example.gleannote

import android.content.Context
import com.example.gleannote.core.contract.ApiClient
import com.example.gleannote.core.contract.AppContainer
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.LocalStore
import com.example.gleannote.core.data.DefaultRepository
import com.example.gleannote.core.database.RoomLocalStore
import com.example.gleannote.core.network.OkHttpApiClient

/*
 * 手写装配容器：避免引入 Hilt / KSP 之外的注解处理器，降低首次构建风险。
 * 依赖方向固定为 repository -> api / local，UI 只取 repository（主文档 3.4）。
 */
class AppContainerImpl(private val context: Context) : AppContainer {
    override val baseUrl: String = BuildConfig.BASE_URL
    override val api: ApiClient by lazy { OkHttpApiClient(context, baseUrl) }
    override val local: LocalStore by lazy { RoomLocalStore(context) }
    override val repository: GleanNoteRepository by lazy { DefaultRepository(api, local, context) }
}
