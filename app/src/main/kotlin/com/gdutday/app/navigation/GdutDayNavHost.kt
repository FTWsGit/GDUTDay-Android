package com.gdutday.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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

/** 底部导航栏的一个条目。 */
private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 底部导航只放**课表**和**成绩**两项。
 *
 * 为什么这么少：底部导航每多一项就挤占一分横向空间，而中文标签
 * （"课表"/"成绩"/"设置"）在窄屏上很容易折行。设置页使用频率极低
 * （配置好一次几个月不动），放进课表页的右上角菜单更合适，
 * 不值得占一个常驻位置。
 *
 * ⚠ 用的是 Material Icons 的**内置**图标（`Icons.Filled.*`），
 * 不引 `material-icons-extended`（那个库 2MB+，只为几个图标不划算）。
 * 内置集合里能用的图标有限，下面这三个是刻意挑的语义近似项：
 * DateRange=课表、Star=成绩、Settings=设置。
 * 要换成真正的自定义图标，应该用 `ImageVector.Builder` 手绘或放 vector drawable，
 * 而不是引入 extended 库。
 */
private val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(Routes.SCHEDULE, "课表", Icons.Filled.DateRange),
    TopLevelDestination(Routes.GRADE, "成绩", Icons.Filled.Star),
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
 * 登录页通过 `AuthRepository.isLoggedIn` 驱动：会话失效时由课表页主动导航过去
 * （见下面的 `LaunchedEffect`）。
 *
 * ## 导航栏的显示逻辑
 *
 * 只在顶层目的地（课表、成绩）显示底部导航栏。
 * 设置页和登录页是"下钻"页面，显示导航栏会让返回语义混乱
 * （用户不知道该点返回箭头还是点导航栏的"课表"）。
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

    androidx.compose.runtime.LaunchedEffect(isLoggedIn, currentDestination?.route) {
        when {
            // 未登录且不在登录页 → 跳登录
            !isLoggedIn && currentDestination?.route != Routes.LOGIN -> {
                navController.navigate(Routes.LOGIN) {
                    // 清空回退栈：从登录页返回不该回到一个没有数据的课表页
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                }
            }
            // 静默重登成功：已在登录页且 isLoggedIn 变 true → 跳课表
            isLoggedIn && currentDestination?.route == Routes.LOGIN -> {
                navController.navigate(Routes.SCHEDULE) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
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
                    // 设置入口放在导航栏最右侧。它不是"顶层目的地"，
                    // 所以选中态永远为 false —— 这正是我们想要的（进入设置页后导航栏消失）。
                    NavigationBarItem(
                        selected = false,
                        onClick = { navController.navigate(Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SCHEDULE,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.SCHEDULE) {
                ScheduleScreen(
                    container = container,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.GRADE) {
                GradeScreen(container = container)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onLogout = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.LOGIN) {
                LoginScreen(
                    container = container,
                    onLoggedIn = {
                        navController.navigate(Routes.SCHEDULE) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                )
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
