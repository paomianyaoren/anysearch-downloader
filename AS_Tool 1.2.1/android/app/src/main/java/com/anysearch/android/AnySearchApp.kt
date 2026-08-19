package com.anysearch.android

import android.app.Application
import com.anysearch.android.core.AppState

/** 应用入口：初始化 AppState（加载设置/Key、构建网络客户端） */
class AnySearchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
    }
}
