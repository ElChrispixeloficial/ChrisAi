package com.chrispixel.chrisai

import android.app.Application
import com.chrispixel.chrisai.data.AppContainer

class ChrisApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}