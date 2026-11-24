package top.aico.wxcacheviewer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.root.RootState

/** Magisk 官方仓库，给还没 root 的用户一个去处。 */
private const val MAGISK_URL = "https://github.com/topjohnwu/Magisk"

/**
 * 没有 root 时的引导页，替代原来「一句错误提示 + 诊断代码铺满全屏」的写法。
 *
 * 这里要说清三件事，否则用户只会反复点重试：
 * 1. 微信的缓存目录是应用私有目录，Android 只允许 root 读——这不是缺了某个可授予的权限；
 * 2. 当前具体卡在哪一步（没 root / 被拒绝 / 超时），对应不同的处理办法；
 * 3. 诊断信息仍然保留，但收进折叠区，只有真要排查时才展开。
 *
 * 不做「跳转到 root 管理器」之类的深链：Magisk、KernelSU、APatch 的包名与入口各不相同，
 * 猜错就是一次打不开的跳转，不如把该点哪里写清楚。
 */
@Composable
fun PermissionGuideScreen(
    reason: RootState.Reason,
    diagnostics: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.root_guide_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = R.string.root_guide_why),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(id = reasonTextRes(reason)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }

        if (reason == RootState.Reason.NO_SU) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.root_guide_steps_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(id = R.string.root_guide_step_1),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(id = R.string.root_guide_step_2),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(id = R.string.root_guide_step_3),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.root_guide_check))
        }

        if (reason == RootState.Reason.NO_SU) {
            TextButton(onClick = { openUrl(context, MAGISK_URL) }) {
                Text(text = stringResource(id = R.string.root_guide_magisk))
            }
        }

        if (!diagnostics.isNullOrBlank()) {
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Text(
                    text = stringResource(
                        id = if (showDiagnostics) {
                            R.string.root_guide_diagnostics_hide
                        } else {
                            R.string.root_guide_diagnostics_show
                        }
                    )
                )
            }
            if (showDiagnostics) {
                Text(
                    text = diagnostics,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(id = R.string.root_guide_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun reasonTextRes(reason: RootState.Reason): Int = when (reason) {
    RootState.Reason.NO_SU -> R.string.root_guide_no_su
    RootState.Reason.DENIED -> R.string.root_guide_denied
    RootState.Reason.TIMEOUT -> R.string.root_guide_timeout
    RootState.Reason.UNKNOWN -> R.string.root_guide_unknown
}
