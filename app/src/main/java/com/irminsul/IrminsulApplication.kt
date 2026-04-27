package com.irminsul

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class IrminsulApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化应用
    }
}
