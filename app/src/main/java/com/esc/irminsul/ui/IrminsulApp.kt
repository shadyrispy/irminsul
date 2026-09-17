package com.esc.irminsul.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.esc.irminsul.MainViewModel
import com.esc.irminsul.R

private object Routes {
    const val CAPTURE = "capture"
    const val PACKETS = "packets"
    const val PACKET_DETAIL = "packet/{packetId}/{commandIndex}"
    fun packetDetail(packetId: Long, commandIndex: Int) = "packet/$packetId/$commandIndex"
}

private val COMPACT_WIDTH_BREAKPOINT = 840.dp

/**
 * App shell: bottom bar on compact screens, side rail on wide ones, between
 * the capture screen and the packet list; packet detail is pushed on top of
 * the back stack.
 */
@Composable
fun IrminsulApp(
    viewModel: MainViewModel,
    captureContent: @Composable () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevelNavigate: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val useRail = LocalConfiguration.current.screenWidthDp.dp >= COMPACT_WIDTH_BREAKPOINT

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (!useRail) AppNavigationBar(currentRoute, topLevelNavigate) }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (useRail) {
                AppNavigationRail(currentRoute, topLevelNavigate)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.CAPTURE,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Routes.CAPTURE) {
                        captureContent()
                    }
                    composable(Routes.PACKETS) {
                        PacketListScreen(
                            viewModel = viewModel,
                            onOpenDetail = { packetId, commandIndex ->
                                navController.navigate(Routes.packetDetail(packetId, commandIndex))
                            }
                        )
                    }
                    composable(
                        route = Routes.PACKET_DETAIL,
                        arguments = listOf(
                            navArgument("packetId") { type = NavType.LongType },
                            navArgument("commandIndex") { type = NavType.IntType }
                        )
                    ) { entry ->
                        PacketDetailScreen(
                            viewModel = viewModel,
                            packetId = entry.arguments?.getLong("packetId") ?: 0L,
                            commandIndex = entry.arguments?.getInt("commandIndex") ?: 0,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            selected = currentRoute == Routes.CAPTURE,
            onClick = { onNavigate(Routes.CAPTURE) },
            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            label = { Text(stringResource(R.string.capture_nav)) }
        )
        NavigationBarItem(
            selected = currentRoute == Routes.PACKETS || currentRoute == Routes.PACKET_DETAIL,
            onClick = { onNavigate(Routes.PACKETS) },
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text(stringResource(R.string.packets_title)) }
        )
    }
}

@Composable
private fun AppNavigationRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationRail {
        NavigationRailItem(
            selected = currentRoute == Routes.CAPTURE,
            onClick = { onNavigate(Routes.CAPTURE) },
            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            label = { Text(stringResource(R.string.capture_nav)) }
        )
        NavigationRailItem(
            selected = currentRoute == Routes.PACKETS || currentRoute == Routes.PACKET_DETAIL,
            onClick = { onNavigate(Routes.PACKETS) },
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text(stringResource(R.string.packets_title)) }
        )
    }
}
