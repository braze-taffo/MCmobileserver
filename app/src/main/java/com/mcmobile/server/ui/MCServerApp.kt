package com.mcmobile.server.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mcmobile.server.ui.console.ConsoleScreen
import com.mcmobile.server.ui.create.CreateScreen
import com.mcmobile.server.ui.home.HomeScreen
import com.mcmobile.server.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val CREATE = "create"
    const val CONSOLE = "console"
    const val SETTINGS = "settings"
    const val FRP = "frp"
    const val PROPERTIES = "properties/{id}"
    const val FILES = "files/{id}"

    fun properties(id: String) = "properties/$id"
    fun files(id: String) = "files/$id"
}

@Composable
fun MCServerApp(vm: AppViewModel = viewModel(), updates: UpdateViewModel = viewModel()) {
    val nav = rememberNavController()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.NavigateConsole -> nav.navigate(Routes.CONSOLE)
                is UiEvent.Error -> android.widget.Toast.makeText(
                    context, event.message, android.widget.Toast.LENGTH_LONG,
                ).show()

                is UiEvent.Info -> android.widget.Toast.makeText(
                    context, event.message, android.widget.Toast.LENGTH_LONG,
                ).show()

                is UiEvent.NeedEula -> { /* EULA 弹窗由 HomeScreen 处理 */ }
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { com.mcmobile.server.ui.home.DashboardScreen(vm, updates, nav) }
        composable(Routes.SERVERS) { HomeScreen(vm, nav) }
        composable(Routes.CREATE) { CreateScreen(vm, nav) }
        composable(Routes.CONSOLE) { ConsoleScreen(vm, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(vm, nav, updates) }
        composable(Routes.FRP) { com.mcmobile.server.ui.frp.FrpScreen(nav) }
        composable(Routes.PROPERTIES) { entry ->
            val id = entry.arguments?.getString("id")
            val inst = id?.let { vm.instanceById(it) }
            if (inst == null) {
                androidx.compose.material3.Text("实例不存在")
            } else {
                com.mcmobile.server.ui.properties.PropertiesScreen(vm, inst, nav)
            }
        }
        composable(Routes.FILES) { entry ->
            val id = entry.arguments?.getString("id")
            val inst = id?.let { vm.instanceById(it) }
            if (inst == null) {
                androidx.compose.material3.Text("实例不存在")
            } else {
                com.mcmobile.server.ui.files.FilesScreen(vm, inst, nav)
            }
        }
    }
    UpdateDialog(updates)
}
