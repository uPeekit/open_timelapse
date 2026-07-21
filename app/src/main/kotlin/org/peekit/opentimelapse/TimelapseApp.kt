package org.peekit.opentimelapse

import android.app.Application
import org.peekit.opentimelapse.actuator.AndroidDeviceActuator
import org.peekit.opentimelapse.actuator.CameraResolver
import org.peekit.opentimelapse.core.engine.Clock
import org.peekit.opentimelapse.data.ConfigRepository
import org.peekit.opentimelapse.data.LogRepository
import org.peekit.opentimelapse.storage.SessionStore
import org.peekit.opentimelapse.storage.StorageAccess

/**
 * The dependency graph, wired by hand.
 *
 * It is about a dozen objects with no cycles and no scoping beyond "one per process", so a
 * DI framework would add a build step and indirection without removing any decisions.
 */
class TimelapseApp : Application() {

    val clock: Clock = Clock { System.currentTimeMillis() }

    val log by lazy { LogRepository() }

    val configRepository by lazy { ConfigRepository(this) }

    val actuator by lazy { AndroidDeviceActuator(this) }

    val cameraResolver by lazy { CameraResolver(this) }

    val storage by lazy { StorageAccess(this) }

    val sessionStore by lazy { SessionStore(this, storage) }
}
