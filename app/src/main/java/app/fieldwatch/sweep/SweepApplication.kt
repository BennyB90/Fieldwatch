package app.fieldwatch.sweep

import android.app.Application
import app.fieldwatch.domain.RadioDb
import app.fieldwatch.BuildConfig
import app.fieldwatch.sweep.network.NetworkController
import app.fieldwatch.sweep.network.NetworkModeHost
import app.fieldwatch.sweep.travel.TravelController
import app.fieldwatch.sweep.travel.TravelModeHost

open class SweepApplication : Application(), TravelModeHost, NetworkModeHost {
    val radioGate = SweepRadioGate()
    lateinit var coordinator: SweepCoordinator
        private set
    final override lateinit var travelController: TravelController
        private set
    final override lateinit var networkController: NetworkController
        private set

    override fun onCreate() {
        super.onCreate()
        RadioDb.init(this)
        coordinator = SweepCoordinator(this, radioGate)
        coordinator.initialize()
        travelController = TravelController(this, canStart = { true }, radioGate = radioGate)
        travelController.initialize()
        if (BuildConfig.SWEEP_NETWORK_ENABLED) {
            networkController = NetworkController(this, radioGate)
            networkController.initialize()
        }
    }
}
