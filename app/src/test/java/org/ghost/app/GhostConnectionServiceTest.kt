package org.ghost.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real service/activity/runtime lifecycles; radio and OS power management need phones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GhostConnectionServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as GhostApplication
    private var controller: ServiceController<GhostConnectionService>? = null

    @After fun close() {
        controller?.destroy()
        app.viewModelStore.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun start(): GhostConnectionService {
        val intent = Intent(app, GhostConnectionService::class.java).setAction(GhostConnectionService.CONNECT)
        controller = Robolectric.buildService(GhostConnectionService::class.java, intent).create().startCommand(0, 1)
        return controller!!.get()
    }

    @Test fun oneRuntimeSurvivesActivityBackgroundingAndDestructionWhileServiceRemainsActive() {
        val service = start()
        val graph = app.graph
        assertTrue(graph.connectionRequested.value)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertSame(graph, (activity.get().application as GhostApplication).graph)
        val dependencies = graph.dependencies
        service.onStartCommand(Intent(app, GhostConnectionService::class.java).setAction(GhostConnectionService.CONNECT), 0, 2)
        assertSame(dependencies, app.graph.dependencies)
        activity.pause().stop().destroy()
        assertTrue(graph.connectionRequested.value)
        assertSame(graph, app.graph)
        val reopened = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertSame(graph, (reopened.get().application as GhostApplication).graph)
        reopened.pause().stop().destroy()
        assertTrue(graph.connectionRequested.value)
        service.onStartCommand(Intent(app, GhostConnectionService::class.java).setAction(GhostConnectionService.DISCONNECT), 0, 3)
        assertFalse(graph.connectionRequested.value)
        assertFalse(graph.nearby.state.value.active)
        assertFalse(graph.nearby.state.value.starting)
        assertTrue(graph.nearby.peers.value.isEmpty())
    }

    @Test fun notificationHasSafeContentAndDisconnectStopsTheRuntime() {
        val service = start()
        val shadow = shadowOf(service)
        assertEquals(GhostConnectionService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        val notification = shadow.lastForegroundNotification
        assertEquals("GHOST is active", notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNotNull(notification.contentIntent)
        val stop = notification.actions.single()
        assertEquals("Disconnect", stop.title.toString())
        assertTrue(shadowOf(stop.actionIntent).isServiceIntent)
        val stopIntent = shadowOf(stop.actionIntent).savedIntent
        assertEquals(GhostConnectionService.DISCONNECT, stopIntent.action)
        service.onStartCommand(stopIntent, 0, 2)
        assertTrue(shadow.isStoppedBySelf)
        assertFalse(app.graph.connectionRequested.value)
        assertTrue(shadow.isForegroundStopped)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(GhostConnectionService.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test fun destructionStopsWorkAndAnEmptyRestartDoesNotReconnect() {
        start()
        assertTrue(app.graph.connectionRequested.value)
        controller!!.destroy(); controller = null
        assertFalse(app.graph.connectionRequested.value)
        controller = Robolectric.buildService(GhostConnectionService::class.java).create()
        assertEquals(android.app.Service.START_NOT_STICKY, controller!!.get().onStartCommand(null, 0, 1))
        assertFalse(app.graph.connectionRequested.value)
        assertTrue(shadowOf(controller!!.get()).isStoppedBySelf)
    }

    @Test
    @Config(sdk = [24, 28, 33, 35])
    fun connectAndDisconnectWorkWithoutDependingOnNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val service = start()
        assertTrue(app.graph.connectionRequested.value)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        service.onStartCommand(Intent(app, GhostConnectionService::class.java)
            .setAction(GhostConnectionService.DISCONNECT), 0, 2)
        assertFalse(app.graph.connectionRequested.value)
    }
}
