package com.example.searchhelper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-level scope running on the main thread.
 * The floating window outlives MainActivity, so its click handlers must not depend on
 * lifecycleScope (which is cancelled when the Activity is destroyed).
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
