package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.theme.LocalStatusColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页。
 *
 * 顶部是开发者名片（Dr.Fox），下方是能力范围、风险提示与技术栈。
 */
@Composable
fun AboutPage() {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "关于",
                kicker = "作者与更新信息",
                subtitle = "PaperLite · 面向个人设备的系统能力探索工具",
            )
        }

        item(key = "author") {
            AuthorCard()
        }

        item(key = "intro") {
            SectionCard(title = "这是什么") {
                BulletRow("PaperLite 复刻了 JC Code V3 的通道机制，Lite 版把操作逐条拆成独立页面。")
                BulletRow("它面向自己拥有或经合法授权的设备，用于系统管理与应用安装辅助。")
                BulletRow("所有高权限操作都需要你先完成无线调试配对。")
            }
        }

        item(key = "channels") {
            SectionCard(title = "能力范围") {
                BulletRow("静默安装 / 卸载：按 Root、无线调试、本地回环的顺序择一执行。")
                BulletRow("管控解除：停用系统的应用管控服务，本次开机内有效。")
                BulletRow("应用管理：查看已安装应用、冻结、清数据、打开详情。")
                BulletRow("设备信息与终端：读取系统属性，执行任意 shell 命令。")
            }
        }

        item(key = "risk") {
            SectionCard(title = "风险提示") {
                BulletRow("此类操作可能影响设备保修状态、系统稳定性或数据安全。")
                BulletRow("停用系统组件、冻结应用等操作存在导致设备异常的风险。")
                BulletRow("请提前自行备份重要资料，因操作不当或系统差异导致的损失由使用者承担。")
            }
        }

        item(key = "tech") {
            val c = LocalStatusColors.current
            SectionCard(title = "技术栈") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(text = "Miuix 0.9.4", color = c.info, onColor = c.onInfo)
                    Pill(text = "Compose", color = c.info, onColor = c.onInfo)
                    Pill(text = "自实现 ADB", color = c.ok, onColor = c.onOk)
                }
                InfoRow("界面框架", "Miuix（NavigationRail 侧边栏）")
                InfoRow("ADB 协议", "自实现，无线调试 + 本地回环共用")
                InfoRow("配对", "TLS-PSK + 公钥交换")
                InfoRow("包名", context.packageName)
                InfoRow("版本", com.codefox.paperlite.BuildConfig.VERSION_NAME)
            }
        }

        item(key = "footer") {
            Text(
                text = "本工具仅供安全研究与学习交流使用，请遵守相关法律法规。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Metrics.pad + 4.dp, vertical = 14.dp),
            )
        }
    }
}

/**
 * 开发者名片：Lite 版开发者 Dr.Fox。
 *
 * 头像用内置的 `fox_avatar.jpg`（drawable-nodpi），圆形裁切；
 * 名片本体沿用澎湃 OS 的浅灰容器，不再加阴影。
 */
@Composable
private fun AuthorCard() {
    val c = LocalStatusColors.current
    SectionCard(title = "Lite 开发者") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.inset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(com.codefox.paperlite.R.drawable.fox_avatar),
                contentDescription = "Dr.Fox 头像",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dr.Fox",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "PaperLite Lite · 开发者",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(text = "Lite", color = c.info, onColor = c.onInfo)
                    Pill(text = "原版：JC Code", color = c.neutral, onColor = c.onNeutral)
                }
            }
        }
        BulletRow("Lite 版在原版通道机制上做了精简：无线调试配对后，能力统一通过终端命令执行。")
    }
}
