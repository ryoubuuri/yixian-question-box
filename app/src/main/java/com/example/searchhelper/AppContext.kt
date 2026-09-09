package com.example.searchhelper

import android.app.Application

/**
 * Minimal application-context holder: the floating window lives outside the Activity
 * lifecycle, so background modules (config, question bank, AI client) need a context
 * that outlives MainActivity.
 */
object AppContext {

    private lateinit var instance: Application

    fun init(application: Application) {
        instance = application
    }

    fun get(): Application = instance
}
