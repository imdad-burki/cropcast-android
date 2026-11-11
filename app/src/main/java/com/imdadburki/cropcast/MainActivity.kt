package com.imdadburki.cropcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.imdadburki.cropcast.feature.player.PlayerScreen
import com.imdadburki.cropcast.core.database.DatabaseProvider
import com.imdadburki.cropcast.core.datastore.SettingsStore
import com.imdadburki.cropcast.core.designsystem.CropcastTheme
import com.imdadburki.cropcast.feature.home.HomeScreen
import com.imdadburki.cropcast.feature.home.HomeViewModel
import com.imdadburki.cropcast.feature.library.LibraryScreen
import com.imdadburki.cropcast.feature.onboarding.OnboardingScreen
import com.imdadburki.cropcast.feature.recording.RecordingSessionScreen
import com.imdadburki.cropcast.feature.recording.SetupScreen
import com.imdadburki.cropcast.feature.region.RegionSelectionScreen
import com.imdadburki.cropcast.feature.settings.SettingsScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val db by lazy { DatabaseProvider.get(this) }
    private val settingsStore by lazy { SettingsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by settingsStore.themeMode.collectAsState(
                initial = com.imdadburki.cropcast.core.common.ThemeMode.SYSTEM,
            )
            CropcastTheme(mode = themeMode) {
                CropcastNav(
                    settingsStore = settingsStore,
                    db = db,
                )
            }
        }
    }
}

@Composable
private fun CropcastNav(
    settingsStore: SettingsStore,
    db: com.imdadburki.cropcast.core.database.AppDatabase,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") {
            SplashScreen(
                onDone = {
                    navController.navigate("gate") {
                        popUpTo("splash") { inclusive = true }
                    }
                },
            )
        }
        composable("gate") {
            val done by settingsStore.onboardingDone.collectAsState(initial = null)
            LaunchedEffect(done) {
                val isDone = done ?: return@LaunchedEffect // wait for DataStore to load
                navController.navigate(if (isDone) "home" else "onboarding") {
                    popUpTo("gate") { inclusive = true }
                }
            }
            Box(modifier = Modifier.fillMaxSize())
        }
        composable("onboarding") {
            OnboardingScreen(
                onFinished = {
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
            )
        }
        composable("home") {
            val homeViewModel: HomeViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        HomeViewModel(db.recordingDao()) as T
                },
            )
            // Mark onboarding done once Home is reached.
            LaunchedEffect(Unit) { settingsStore.setOnboardingDone(true) }
            HomeScreen(
                viewModel = homeViewModel,
                onStartRecording = { navController.navigate("setup") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenRecording = { id -> navController.navigate("player/$id") },
            )
        }
        composable("setup") {
            SetupScreen(
                settingsDao = db.recordingSettingsDao(),
                onContinueToRegion = { navController.navigate("region") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("region") {
            RegionSelectionScreen(
                settingsDao = db.recordingSettingsDao(),
                onConfirm = { navController.navigate("session") },
                onCancel = { navController.popBackStack() },
            )
        }
        composable("session") {
            RecordingSessionScreen(
                settingsDao = db.recordingSettingsDao(),
                settingsStore = settingsStore,
                onOpenLibrary = { navController.navigate("library") },
                onBackHome = { navController.popBackStack("home", false) },
            )
        }
        composable("settings") {
            SettingsScreen(
                store = settingsStore,
                recordingDao = db.recordingDao(),
                appVersion = "1.0.0",
                onBack = { navController.popBackStack() },
            )
        }
        composable("library") {
            LibraryScreen(
                dao = db.recordingDao(),
                onOpenRecording = { id -> navController.navigate("player/$id") },
            )
        }
        composable(
            "player/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            PlayerScreen(
                recordingId = id,
                dao = db.recordingDao(),
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack("library", false) },
            )
        }
    }
}

/** Splash (spec §4, Screen 01): wordmark + red dot + tagline, fast startup. */
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(900)
        onDone()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CROPCAST", style = MaterialTheme.typography.displayLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Record only what matters.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
