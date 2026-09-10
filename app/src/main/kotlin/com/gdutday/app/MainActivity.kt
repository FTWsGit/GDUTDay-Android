package com.gdutday.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gdutday.app.navigation.GdutDayNavHost
import com.gdutday.core.ui.GdutDayTheme

/**
 * 唯一的 Activity。
 *
 * ## enableEdgeToEdge
 *
 * Android 15（API 35）起，`targetSdk 35` 的应用**强制**边到边显示，
 * `android:windowOptOutEdgeToEdgeEnforcement` 已废弃。
 * 与其被动接受，不如显式调用 [enableEdgeToEdge] 并在 Compose 里用
 * `WindowInsets` 正确处理 —— 课表页的顶部周次栏和底部导航都要避开状态栏/手势区。
 *
 * ## 为什么不在这里读任何数据
 *
 * `onCreate` 里只做 `setContent`。数据流从 `GdutDayNavHost` 内部的
 * Composable 开始收集，这样：
 * - Activity 创建耗时接近 0；
 * - 首帧能立刻画出来（哪怕是加载态），而不是等 DataStore/Room 读完；
 * - 主题切换、字体缩放等配置变更不会触发数据重读。
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            GdutDayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GdutDayNavHost(container = appContainer)
                }
            }
        }
    }
}
