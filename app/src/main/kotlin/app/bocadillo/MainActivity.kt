package app.bocadillo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import app.bocadillo.editor.EditorScreen
import app.bocadillo.home.HomeScreen
import app.bocadillo.home.HomeViewModel
import app.bocadillo.ui.theme.BocadilloTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Picture shared/sent to the app (ACTION_SEND / ACTION_EDIT), consumed by the home screen. */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // dark bars always, like the classic screens
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK), androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK))
        if (savedInstanceState == null) handleIntent(intent)
        val app = application as BocadilloApp
        setContent {
            BocadilloTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        val vm: HomeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
                        })
                        LaunchedEffect(Unit) {
                            incoming.collect { uri -> if (uri != null) { incoming.value = null; vm.importPicture(uri, getString(R.string.untitled)) } }
                        }
                        HomeScreen(vm) { id -> nav.navigate("editor/$id") { launchSingleTop = true } }
                    }
                    composable("editor/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        EditorScreen(id) { nav.popBackStack() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_EDIT -> intent.data
            else -> null
        }
        if (uri != null) incoming.value = uri
    }
}
