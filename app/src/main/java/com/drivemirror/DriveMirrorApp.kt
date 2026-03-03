package com.drivemirror

import android.app.Application
import android.util.Log

class DriveMirrorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("DriveMirror", "App started")
    }
}
