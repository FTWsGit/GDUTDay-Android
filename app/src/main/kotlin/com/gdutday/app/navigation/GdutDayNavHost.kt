package com.gdutday.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import com.gdutday.data.repository.AppContainer
import com.gdutday.feature.auth.LoginScreen
import com.gdutday.feature.grade.GradeScreen
import com.gdutday.feature.schedule.ScheduleScreen
import com.gdutday.feature.settings.SettingsScreen
import com.gdutday.feature.toolbox.FreeRoomScreen
import com.gdutday.feature.toolbox.ToolboxScreen

/** 底部导航栏的一个条目。 */
private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 底部导航栏的入口：课表、考试、工具箱、设置。
 *
 * 各自都是**顶层目的地**，持有独立的回退栈状态（`saveState / restoreState`），
 * 从任一 Tab 切到另一个不会重建 Composable。
 *
 * ⚠ 用的是 Material Icons 的**内置**图标（`Icons.Filled.*`），
 * 不引 `material-icons-extended`（那个库 2MB+，只为几个图标不划算）。
 */
private val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(Routes.SCHEDULE, "课表", Icons.Filled.DateRange),
    TopLevelDestination(Routes.GRADE, "考试", Icons.Filled.Star),
    TopLevelDestination(Routes.TOOLBOX, "工具箱", Icons.Filled.Build),
    TopLevelDestination(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

/**
 * 应用的导航图。
 *
 * ## 起始路由是课表，不是登录
 *
 * 这是**启动速度**的关键决策之一：
 * - 未登录时课表页显示空状态 + 登录入口，用户看到的是"App 已经打开了"；
 * - 已登录时课表页立刻显示上次缓存的数据，同步在后台进行。
 *
 * 如果起始路由是登录页，每次冷启动都要先解密会话、判断登录态，
 * 才能决定显示什么 —— 那段等待是纯粹的白屏。
 *
 * ## 导航栏的显示逻辑
 *
 * 底部导航栏对三个顶层目的地（课表、成绩、设置）均可见，
 * 登录页作为临时页面则隐藏导航栏。
 */
@Composable
fun GdutDayNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showBottomBar = currentDestination?.hierarchy?.any { dest ->
        TOP_LEVEL_DESTINATIONS.any { it.route == dest.route }
    } == true

    // 会话失效时自动跳登录页。用 LaunchedEffect 而不是在 Composable 里直接调用
    // navController.navigate —— 后者会在每次重组时都触发导航。
    val isLoggedIn by container.authRepository.isLoggedIn
        .collectAsLifecycleState(initial = true)

    // 冷启动时 `isLoggedIn` 的第一个有效值是 `false`（`sessionStore` 还没解出文件，
    // StateFlow 初始值为 null → `it != null` 为 false）。此时立即导航会闪现登录页。
    // 给 sessionStore 一点时间去读盘和解密，等真正确定没有会话再跳转。
    androidx.compose.runtime.LaunchedEffect(isLoggedIn, currentDestination?.route) {
        when {
            !isLoggedIn && currentDestination?.route != Routes.LOGIN -> {
                kotlinx.coroutines.delay(800)
                if (!isLoggedIn) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                    }
                }
            }
            isLoggedIn && currentDestination?.route == Routes.LOGIN -> {
                navController.navigate(Routes.SCHEDULE) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                    // 双保险：万一同帧内还有别的导航路径触发，也不会叠出第二个 SCHEDULE。
                    launchSingleTop = true
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_LEVEL_DESTINATIONS.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(dest.route) {
                                        // 从起始目的地弹出，避免回退栈里堆积多份课表页
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SCHEDULE,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(
                Routes.SCHEDULE,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                ScheduleScreen(
                    container = container,
                    onOpenSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(
                Routes.GRADE,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                GradeScreen(container = container)
            }
            composable(
                Routes.TOOLBOX,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                ToolboxScreen(
                    container = container,
                    onOpenFreeRoom = { navController.navigate(Routes.FREE_ROOM) },
                )
            }
            composable(Routes.FREE_ROOM) {
                FreeRoomScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable(
                Routes.SETTINGS,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                SettingsScreen(
                    container = container,
                    onLogout = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    },
                    onBack = {},
                )
            }
            composable(Routes.LOGIN) {
                // 不传 onLoggedIn：登录成功后的导航**只由上面 isLoggedIn 的 LaunchedEffect 负责**。
                // 若这里再导航一次，两次几乎同时执行，回退栈会叠出两个 SCHEDULE，
                // 用户按返回键会落到另一个 SCHEDULE 而不是退出 App。
                LoginScreen(container = container)
            }
        }
    }
}

/**
 * `collectAsStateWithLifecycle` 的带默认值封装。
 *
 * 之所以要封装：`isLoggedIn` 的初值必须是 **true** 而不是 false。
 * 如果初值是 false，冷启动的第一帧就会满足上面 `LaunchedEffect` 的条件，
 * 在会话还没解密出来之前就把用户导航到登录页 —— 表现为"每次打开 App 都闪一下登录页"。
 * 初值给 true，等 Flow 真正发出第一个值（解密结果）后再决定，就不会闪。
 */
@Composable
private fun <T> Flow<T>.collectAsLifecycleState(initial: T): State<T> =
    collectAsStateWithLifecycle(initialValue = initial)
