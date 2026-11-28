package ai.zeroclaw.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import ai.zeroclaw.app.data.AppViewModel
import ai.zeroclaw.app.data.ChatRuntime
import ai.zeroclaw.app.ui.ZeroClawRoot
import ai.zeroclaw.app.ui.theme.ZeroClawTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChatRuntime.init(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // The device link runs as a foreground service; its notification is
        // how the operator sees that the phone is reachable.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        setContent {
            ZeroClawTheme(darkTheme = true) {
                ZeroClawRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ChatRuntime.setVisible(true)
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        ChatRuntime.setVisible(false)
    }

    override fun onDestroy() {
        // Task removal can destroy the activity without an orderly stop.
        // A configuration change recreates it immediately, so only mark the
        // app hidden for a real finish/removal.
        if (!isChangingConfigurations) ChatRuntime.setVisible(false)
        super.onDestroy()
    }
}
