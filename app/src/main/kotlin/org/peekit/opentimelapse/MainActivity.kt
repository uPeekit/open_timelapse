package org.peekit.opentimelapse

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.core.render.RenderSpec
import org.peekit.opentimelapse.data.RenderPhase
import org.peekit.opentimelapse.render.RenderService
import org.peekit.opentimelapse.service.TimelapseService
import org.peekit.opentimelapse.ui.LicensesActivity
import org.peekit.opentimelapse.ui.MainActions
import org.peekit.opentimelapse.ui.MainScreen
import org.peekit.opentimelapse.ui.SessionActions
import org.peekit.opentimelapse.ui.SetupCheck
import org.peekit.opentimelapse.ui.SetupChecks

class MainActivity : ComponentActivity() {

    private val app by lazy { application as TimelapseApp }

    private var checks by mutableStateOf<List<SetupCheck>>(emptyList())
    private var sessions by mutableStateOf<List<SessionManifest>>(emptyList())

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
                val render by app.renderState.state.collectAsStateWithLifecycle()

                // Open the finished video once per completion, when asked. rememberSaveable
                // survives a rotation so it does not reopen; a completionId only advances on a
                // genuinely new render.
                var lastOpened by rememberSaveable { mutableStateOf(0L) }
                LaunchedEffect(render.completionId, config.openVideoAfterRender) {
                    val id = render.completionId
                    if (id > lastOpened &&
                        render.phase == RenderPhase.SUCCESS &&
                        config.openVideoAfterRender &&
                        render.outputUri != null
                    ) {
                        lastOpened = id
                        openVideo(render.outputUri!!)
                    }
                }

                Scaffold { padding ->
                    MainScreen(
                        config = config,
                        checks = checks,
                        log = log,
                        sessions = sessions,
                        render = render,
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
                            sessionActions = sessionActions(),
                            onCalibrate = {
                                TimelapseService.send(this, TimelapseService.ACTION_CALIBRATE)
                            },
                            onDeclineCalibration = {
                                lifecycleScope.launch {
                                    app.configRepository.update {
                                        it.copy(calibration = it.calibration.copy(declined = true))
                                    }
                                }
                            },
                            onOpenLicenses = {
                                startActivity(Intent(this@MainActivity, LicensesActivity::class.java))
                            },
                            onTestWebhook = { action ->
                                lifecycleScope.launch {
                                    app.charging.test(app.configRepository.current().charging, action)
                                }
                            },
                            onShareLog = ::shareLog,
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
            sessions = app.sessionStore.loadAll()
        }
    }

    private fun sessionActions() = SessionActions(
        onRender = { session ->
            lifecycleScope.launch {
                val stored = app.configRepository.current().customRenderCommand
                RenderService.render(this@MainActivity, session.id, RenderSpec(customCommand = stored))
            }
        },
        onDelete = { session ->
            lifecycleScope.launch {
                app.sessionStore.delete(session.id)
                sessions = app.sessionStore.loadAll()
            }
        },
        onDeletePhotos = { session ->
            lifecycleScope.launch {
                app.sessionStore.deletePhotos(session)
                sessions = app.sessionStore.loadAll()
            }
        },
    )

    private fun openVideo(uriString: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { app.log.message("No app available to open the video") }
    }

    private fun shareLog() {
        val file = app.log.exportFile() ?: return
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OpenTimelapse log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share log")) }
    }

    private fun openFix(check: SetupCheck) {
        val intent = check.fix ?: return
        runCatching { startActivity(intent) }
            .onFailure {
                // Some OEMs omit these screens; fall back to the app's own settings page.
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
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
