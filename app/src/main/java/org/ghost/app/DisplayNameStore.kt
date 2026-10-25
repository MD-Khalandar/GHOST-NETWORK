package org.ghost.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** A public label only. Signing identity and resource ownership never depend on it. */
class DisplayNameStore(context: Context) {
    private val preferences = context.getSharedPreferences("ghost_profile", Context.MODE_PRIVATE)
    private val mutableName = MutableStateFlow(preferences.getString("display_name", "").orEmpty())
    val name = mutableName.asStateFlow()
    suspend fun save(value: String) = withContext(Dispatchers.IO) {
        val normalized = value.trim()
        require(normalized.length <= 40 && normalized.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            "Use up to 40 characters without line breaks or control characters."
        }
        check(preferences.edit().putString("display_name", normalized).commit()) { "Could not save your name." }
        mutableName.value = normalized
    }
}
