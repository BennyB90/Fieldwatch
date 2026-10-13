package app.fieldwatch.sweep

import android.app.Application
import app.fieldwatch.domain.RadioDb
import app.fieldwatch.sweep.travel.TravelController
import app.fieldwatch.sweep.travel.TravelModeHost

open class SweepApplication : Application(), TravelModeHost {
    val radioGate = SweepRadioGate()
    lateinit var coordinator: SweepCoordinator
        private set
    final override lateinit var travelController: TravelController
        private set

    override fun onCreate() {
        super.onCreate()
        RadioDb.init(this)
        coordinator = SweepCoordinator(this, radioGate)
        coordinator.initialize()
        travelController = TravelController(this, canStart = { true }, radioGate = radioGate)
        travelController.initialize()
    }
}
