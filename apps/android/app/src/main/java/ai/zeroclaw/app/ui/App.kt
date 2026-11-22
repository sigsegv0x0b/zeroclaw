package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.zeroclaw.app.data.AppViewModel
import ai.zeroclaw.app.data.SocketState
import ai.zeroclaw.app.data.UiState
import ai.zeroclaw.app.ui.theme.Zc
import kotlinx.coroutines.launch

@Composable
fun ZeroClawRoot(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = Zc.Canvas) {
        when {
            state.booting -> SplashScreen()
            state.settingsOpen -> SettingsScreen(
                state = state,
                onBack = { viewModel.openSettings(false) },
                onReconnect = viewModel::reconnect,
                onForget = viewModel::forgetGateway,
                onNodeEnabled = viewModel::setNodeEnabled,
                onFolderPicked = viewModel::saveNodeFolder,
                onVoiceBackend = viewModel::setVoiceBackend,
                onMtlsEnabled = viewModel::setMtlsEnabled,
                onMtlsGatewayId = viewModel::setMtlsGatewayId,
                onMtlsImport = viewModel::importMtlsMaterial,
            )
            !state.paired -> OnboardingScreen(
                booting = state.booting,
                error = state.error,
                deviceName = state.deviceName,
                onPair = viewModel::pair,
                onTokenConnect = viewModel::reconnect,
                onDismissError = viewModel::dismissError,
            )
            else -> MainShell(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(64)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "ZeroClaw",
                style = MaterialTheme.typography.titleLarge,
                color = Zc.Text,
            )
            Spacer(Modifier.height(22.dp))
            CircularProgressIndicator(
                color = Zc.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.height(22.dp).width(22.dp),
            )
        }
    }
}

@Composable
private fun MainShell(state: UiState, viewModel: AppViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.sessionId, state.agent) {
        if (drawerState.isOpen) drawerState.close()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        scrimColor = Zc.Canvas.copy(alpha = 0.6f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Zc.Surface,
                drawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
                modifier = Modifier.width(304.dp),
            ) {
                SidebarContent(
                    state = state,
                    onSelectAgent = viewModel::selectAgent,
                    onNewChat = {
                        viewModel.newChat()
                        scope.launch { drawerState.close() }
                    },
                    onOpenSession = { session ->
                        viewModel.openSession(
                            sessionId = session.sessionId,
                            agentAlias = session.agentAlias ?: state.agent,
                            loadHistory = true,
                        )
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        viewModel.openSettings(true)
                        scope.launch { drawerState.close() }
                    },
                    onRefreshSessions = { viewModel.refreshSessions() },
                )
            }
        },
    ) {
        ChatScreen(
            state = state,
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } },
        )
    }
}
