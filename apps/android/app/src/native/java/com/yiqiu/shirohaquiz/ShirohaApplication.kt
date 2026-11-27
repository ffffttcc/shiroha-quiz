package com.yiqiu.shirohaquiz

import android.app.Application
import android.content.Context
import android.util.Log
import com.yiqiu.shirohaquiz.ui.text.latex.LatexEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ShirohaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 只初始化引擎，自检移到 MainActivity 延迟执行
        LatexEngine.init(this)
    }
}