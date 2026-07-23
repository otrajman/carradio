package com.carradio.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.carradio.app.data.SettingsStore

class CarRadioApp : Application() {

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "carradio_service"
    }
}
