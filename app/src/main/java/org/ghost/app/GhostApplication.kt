package org.ghost.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** One runtime shared by screens and the connection service for this process. */
class GhostApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val graph: AppGraph
        get() = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[AppGraph::class.java]

    override fun onTerminate() {
        viewModelStore.clear()
        super.onTerminate()
    }
}
