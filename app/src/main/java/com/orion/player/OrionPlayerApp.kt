package com.orion.player

import android.app.Application
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.orion.player.util.NetworkDiagnostics
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OrionPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NetworkDiagnostics.logStartupConfig()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("orion_player")
        }
        Log.i("OrionPlayer", "Application started")
    }
}
