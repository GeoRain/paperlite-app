package com.codefox.paperlite.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import com.codefox.paperlite.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 彩蛋隐藏菜单的全局状态。
 *
 * 解锁方式：连点侧边栏左上角的 APK 图标 5 次（带 1.5 秒时间窗）。
 * 解锁后所有页面的汉堡菜单末尾会多出隐藏入口，再次连点收起。
 *
 * 用进程级 StateFlow 而不是每个页面各记一份：
 * 用户在概览页解锁，切到 Shizuku 页也应该立刻能看到，
 * 而且不会跟页面重组、AnimatedContent 的生命周期纠缠。
 */
object EasterEgg {

    const val REQUIRED_TAPS = 5
    const val TAP_WINDOW_MS = 1500L

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun setUnlocked(v: Boolean) {
        _unlocked.value = v
    }

    /** 由 Settings 在启动时把持久化状态灌进来；之后以 StateFlow 为准。 */
    fun restore(persisted: Boolean) {
        _unlocked.value = persisted
    }
}

/**
 * 隐藏菜单项。
 *
 * 3 张图各对应一个**真有用的**动作，不是摆设：
 * - 绿色小人 → 开关壁纸背景
 * - 金色卡片 → 轮换配色来源
 * - 鸟巢 → 打开 JCCode 官网
 */
data class HiddenEntry(
    val title: String,
    val summary: String,
    val iconRes: Int,
    val onClick: () -> Unit,
)

/** 隐藏菜单项的供给：由 [com.codefox.paperlite.ui.PaperLiteApp] 注入，页面不自己拼。 */
val LocalHiddenMenu = staticCompositionLocalOf<List<HiddenEntry>> { emptyList() }

/** 解锁状态，供菜单判断要不要把隐藏项拼进去。 */
@Composable
fun rememberEggUnlocked(): State<Boolean> = EasterEgg.unlocked.collectAsState()

/** 三张彩蛋图标的资源 id（集中放这里，别散在页面里）。 */
object EggIcons {
    val Runner = R.drawable.egg_runner
    val Card = R.drawable.egg_card
    val Nest = R.drawable.egg_nest
}
