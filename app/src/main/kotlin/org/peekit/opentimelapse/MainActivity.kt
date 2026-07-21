package org.peekit.opentimelapse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.service.TimelapseService
import org.peekit.opentimelapse.ui.MainActions
import org.peekit.opentimelapse.ui.MainScreen
import org.peekit.opentimelapse.ui.SetupCheck
import org.peekit.opentimelapse.ui.SetupChecks

class MainActivity : ComponentActivity() {

    private val app by lazy { application as TimelapseApp }

    private var checks by mutableStateOf<List<SetupCheck>>(emptyList())

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshChecks()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val config by app.configRepository.config
                    .collectAsStateWithLifecycle(initialValue = TimelapseConfig())
                val log by app.log.log.collectAsStateWithLifecycle()

                Scaffold { padding ->
                    MainScreen(
                        config = config,
                        checks = checks,
                        log = log,
                        actions = MainActions(
                            onStart = { TimelapseService.send(this, TimelapseService.ACTION_START) },
                            onStop = { TimelapseService.send(this, TimelapseService.ACTION_STOP) },
                            onSingleCycle = {
                                TimelapseService.send(this, TimelapseService.ACTION_SINGLE_CYCLE)
                            },
                            onFix = ::openFix,
                            onConfigChange = { transform ->
                                lifecycleScope.launch { app.configRepository.update(transform) }
                            },
                        ),
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }

        askForRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-read on every return: the user may have just changed something in Settings,
        // and several of these cannot be observed any other way.
        refreshChecks()
    }

    private fun refreshChecks() {
        lifecycleScope.launch {
            checks = SetupChecks.evaluate(this@MainActivity, app.configRepository.current())
        }
    }

    private fun openFix(check: SetupCheck) {
        val intent = check.fix ?: return
        runCatching { startActivity(intent) }
            .onFailure {
                // Some OEMs omit these screens; fall back to the app's own settings page.
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        )
                    )
                }
            }
    }

    private fun askForRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray())
    }
}
