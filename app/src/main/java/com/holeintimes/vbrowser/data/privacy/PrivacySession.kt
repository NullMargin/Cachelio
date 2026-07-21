package com.holeintimes.vbrowser.data.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped access to hidden files. It deliberately starts locked whenever
 * Android creates a new application process and is never persisted.
 */
class PrivacySession {
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun unlock() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }
}
