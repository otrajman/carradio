package com.carradio.app.car

import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point (androidx.car.app COMMUNICATION category).
 * The host validator allows all hosts in this v1 build — tighten before production release.
 */
class CarRadioCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarRadioSession()
}

class CarRadioSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen =
        RadarScreen(carContext)
}
