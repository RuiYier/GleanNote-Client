package com.example.gleannote

import android.app.Application
import com.example.gleannote.core.contract.AppContainer
import com.example.gleannote.core.sync.SyncScheduler

/*
 * 应用入口：持有全局依赖装配；不在 Application 中发起网络调用或启动轮询。
 * 同步触发时机见主文档 6.7（登录成功、回前台、网络恢复）。
 */
class GleanNoteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainerImpl(this)
        SyncScheduler.schedule(this)
    }
}
