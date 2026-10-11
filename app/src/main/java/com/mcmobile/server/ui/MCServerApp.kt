package com.mcmobile.server.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.*
import androidx.compose.material3.*
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
    const val CUSTOM_APK = "custom-apk"
    const val FRP = "frp"
    const val PACK_IMPORT = "pack-import"
    const val MANAGE = "manage/{id}"
    const val PLAYERS = "players/{id}"
    const val ADDONS = "addons/{id}"
    const val WORLDS = "worlds/{id}"
    const val LOGS = "logs/{id}"
    const val TASKS = "tasks/{id}"
    fun manage(id: String) = "manage/$id"
    fun players(id: String) = "players/$id"
    fun addons(id: String) = "addons/$id"
    fun worlds(id: String) = "worlds/$id"
    fun logs(id: String) = "logs/$id"
    fun tasks(id: String) = "tasks/$id"
    const val PROPERTIES = "properties/{id}"
    const val FILES = "files/{id}?initial={initial}"
    const val INSTANCE = "instance/{id}"

    fun properties(id: String) = "properties/$id"
    fun files(id: String) = "files/$id"
    fun instance(id: String) = "instance/$id"
}

@Composable
fun MCServerApp(vm: AppViewModel = viewModel(), updates: UpdateViewModel = viewModel()) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val instances by vm.instances.collectAsState()
    var eula by remember { mutableStateOf<com.mcmobile.server.data.ServerInstance?>(null) }

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

                is UiEvent.NeedEula -> eula = event.instance
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { com.mcmobile.server.ui.home.DashboardScreen(vm, updates, nav) }
        composable(Routes.SERVERS) { HomeScreen(vm, nav) }
        composable(Routes.CREATE) { CreateScreen(vm, nav) }
        composable(Routes.PACK_IMPORT) { com.mcmobile.server.ui.create.PackImportScreen(vm, nav) }
        composable(Routes.CONSOLE) { ConsoleScreen(vm, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(vm, nav, updates) }
        composable(Routes.CUSTOM_APK) { com.mcmobile.server.ui.settings.CustomApkScreen(nav) }
        composable(Routes.FRP) { com.mcmobile.server.ui.frp.FrpScreen(nav) }
        listOf(Routes.MANAGE, Routes.PLAYERS, Routes.ADDONS, Routes.WORLDS, Routes.LOGS, Routes.TASKS).forEach { route ->
            composable(route) { entry ->
                val instance = instances.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (instance == null) Text("实例不存在") else when (route) {
                    Routes.MANAGE -> com.mcmobile.server.ui.manage.ServerOverviewScreen(vm, instance, nav)
                    Routes.PLAYERS -> com.mcmobile.server.ui.manage.PlayersScreen(vm, instance, nav)
                    Routes.ADDONS -> com.mcmobile.server.ui.manage.AddonsScreen(vm, instance, nav)
                    Routes.WORLDS -> com.mcmobile.server.ui.manage.WorldsScreen(vm, instance, nav)
                    Routes.LOGS -> com.mcmobile.server.ui.manage.LogsScreen(vm, instance, nav)
                    Routes.TASKS -> com.mcmobile.server.ui.manage.TasksScreen(vm, instance, nav)
                }
            }
        }
        composable(Routes.PROPERTIES) { entry ->
            val id = entry.arguments?.getString("id")
            val inst = id?.let { vm.instanceById(it) }
            if (inst == null) {
                androidx.compose.material3.Text("实例不存在")
            } else {
                com.mcmobile.server.ui.properties.PropertiesScreen(vm, inst, nav)
            }
        }
        composable(Routes.FILES, arguments = listOf(androidx.navigation.navArgument("initial") { defaultValue = "" })) { entry ->
            val id = entry.arguments?.getString("id")
            val inst = id?.let { vm.instanceById(it) }
            if (inst == null) {
                androidx.compose.material3.Text("实例不存在")
            } else {
                com.mcmobile.server.ui.files.FileBrowserScreen(vm, inst, nav, entry.arguments?.getString("initial").orEmpty())
            }
        }
        composable(Routes.INSTANCE) { entry ->
            val id = entry.arguments?.getString("id")
            val inst = id?.let { vm.instanceById(it) }
            if (inst == null) {
                androidx.compose.material3.Text("实例不存在")
            } else {
                com.mcmobile.server.ui.instance.InstanceSettingsScreen(vm, inst, nav)
            }
        }
    }
    eula?.let { instance -> AlertDialog(onDismissRequest = { eula = null }, title = { Text("Mojang EULA") },
        text = { Text("运行服务端需要同意 Minecraft 最终用户许可协议：https://www.minecraft.net/eula。是否同意并启动？") },
        confirmButton = { TextButton(onClick = { eula = null; vm.acceptEula(instance) }) { Text("同意并启动") } },
        dismissButton = { TextButton(onClick = { eula = null }) { Text("取消") } }) }
    UpdateDialog(updates)
}
