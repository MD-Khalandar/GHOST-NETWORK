package org.ghost.app

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayNameStoreTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @After fun clear() { context.getSharedPreferences("ghost_profile", 0).edit().clear().commit() }
    @Test fun namePersistsAcrossStoreRecreationAndCanBeCleared() = runBlocking {
        val profile = DisplayNameStore(context)
        profile.save("  Asha कुमार  ")
        assertEquals("Asha कुमार", profile.name.value)
        val reopened = DisplayNameStore(context)
        assertEquals("Asha कुमार", reopened.name.value)
        reopened.save(" ")
        assertEquals("", DisplayNameStore(context).name.value)
    }
    @Test fun invalidNamesDoNotOverwriteTheSavedName() = runBlocking {
        val profile = DisplayNameStore(context); profile.save("Asha")
        for (name in listOf("x".repeat(41), "Fake\nName", "Fake\u202eName"))
            assertTrue(runCatching { profile.save(name) }.isFailure)
        assertEquals("Asha", DisplayNameStore(context).name.value)
    }
}
