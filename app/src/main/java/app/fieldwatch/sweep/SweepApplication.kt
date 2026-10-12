package app.fieldwatch.sweep

import android.app.Application
import app.fieldwatch.domain.RadioDb

class SweepApplication : Application() {
    lateinit var coordinator: SweepCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        RadioDb.init(this)
        coordinator = SweepCoordinator(this)
        coordinator.initialize()
    }
}
