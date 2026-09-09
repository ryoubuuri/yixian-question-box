package com.example.searchhelper

import android.app.Application

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        // Built-in + previously imported bank. Small files, so loading on the main
        // thread at startup is fine; matching always runs off the main thread.
        QuestionBank.loadAll(this)
    }
}
