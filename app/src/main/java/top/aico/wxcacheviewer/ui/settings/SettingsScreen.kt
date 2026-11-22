package top.aico.wxcacheviewer.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.domain.ExportLocation
import top.aico.wxcacheviewer.ui.common.formatFileSize
import top.aico.wxcacheviewer.viewmodel.SettingsUiState

/** 仓库地址同时用于「开源仓库」与「版本」两项的跳转。 */
private const val REPOSITORY_URL = "https://github.com/zsyo/WXCacheViewer"

/** 开发者主页，点「开发者」一行时打开。 */
private const val DEVELOPER_URL = "https://github.com/zsyo"

private enum class AboutDialog { LICENSE, PRIVACY, DISCLAIMER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onExportRootChosen: (path: String, uri: String) -> Unit,
    onResetExportRoot: () -> Unit,
    onRefreshCacheSize: () -> Unit,
    onClearCache: () -> Unit,
    onNoticeShown: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<AboutDialog?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val versionName = remember { versionName(context) }

    // 关于页的链接一律走系统浏览器，没有能处理的应用时给提示
    val openLink: (String) -> Unit = { url ->
        if (!openUrl(context, url)) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.settings_link_failed))
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = if (uri.authority == ExportLocation.PROVIDER_AUTHORITY) {
            runCatching { DocumentsContract.getTreeDocumentId(uri) }
                .getOrNull()
                ?.let(ExportLocation::resolve)
        } else {
            null
        }
        if (path == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    resources.getString(R.string.settings_export_root_unsupported)
                )
            }
            return@rememberLauncherForActivityResult
        }
        // 持久化授权，换进程后 SAF 目录仍然可写
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        onExportRootChosen(path, uri.toString())
    }

    LaunchedEffect(state.notice) {
        val message = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onNoticeShown()
    }

    // 每次进设置页重新统计，清理过或刚导出过都能看到最新值
    LaunchedEffect(Unit) {
        onRefreshCacheSize()
    }

    BackHandler { onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(id = R.string.player_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle(stringResource(id = R.string.settings_section_data))
            ExportRootCard(
                state = state,
                onChange = { picker.launch(null) },
                onReset = onResetExportRoot
            )
            CacheCard(
                state = state,
                onClear = { showClearConfirm = true }
            )

            SectionTitle(stringResource(id = R.string.settings_section_about))
            AboutCard(
                versionName = versionName,
                onOpenRepository = { openLink(REPOSITORY_URL) },
                onOpenDeveloper = { openLink(DEVELOPER_URL) },
                onShowLicense = { dialog = AboutDialog.LICENSE },
                onShowPrivacy = { dialog = AboutDialog.PRIVACY },
                onShowDisclaimer = { dialog = AboutDialog.DISCLAIMER }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(id = R.string.settings_cache_confirm_title)) },
            text = { Text(stringResource(id = R.string.settings_cache_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearCache()
                    }
                ) {
                    Text(stringResource(id = R.string.settings_cache_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    val currentDialog = dialog
    if (currentDialog != null) {
        val (titleRes, contentRes) = when (currentDialog) {
            AboutDialog.LICENSE -> R.string.settings_about_license to R.string.settings_license_content
            AboutDialog.PRIVACY -> R.string.settings_about_privacy to R.string.settings_privacy_content
            AboutDialog.DISCLAIMER ->
                R.string.settings_about_disclaimer to R.string.settings_disclaimer_content
        }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(id = titleRes)) },
            text = { Text(stringResource(id = contentRes)) },
            confirmButton = {
                TextButton(onClick = { dialog = null }) {
                    Text(stringResource(id = R.string.settings_close))
                }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ExportRootCard(
    state: SettingsUiState,
    onChange: () -> Unit,
    onReset: () -> Unit
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(id = R.string.settings_export_root_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    id = if (state.exportRootCustom) {
                        R.string.settings_export_root_custom
                    } else {
                        R.string.settings_export_root_default
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.exportRootPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.settings_export_root_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.exportRootCustom) {
                TextButton(onClick = onReset) {
                    Text(stringResource(id = R.string.settings_export_root_reset))
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            FilledTonalButton(onClick = onChange) {
                Text(stringResource(id = R.string.settings_export_root_choose))
            }
        }
    }
}

@Composable
private fun CacheCard(
    state: SettingsUiState,
    onClear: () -> Unit
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(id = R.string.settings_cache_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (state.cacheScanning) {
                    stringResource(id = R.string.settings_cache_scanning)
                } else {
                    stringResource(id = R.string.settings_cache_size, formatFileSize(state.cacheBytes))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(id = R.string.settings_cache_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onClear,
                enabled = !state.cacheClearing && !state.cacheScanning && state.cacheBytes > 0
            ) {
                if (state.cacheClearing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(id = R.string.settings_cache_clearing))
                } else {
                    Text(stringResource(id = R.string.settings_cache_clear))
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    versionName: String,
    onOpenRepository: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onShowLicense: () -> Unit,
    onShowPrivacy: () -> Unit,
    onShowDisclaimer: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(id = R.string.settings_about_version) + " " + versionName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(id = R.string.settings_about_description),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AboutRow(
                title = stringResource(id = R.string.settings_about_repo),
                value = "zsyo/WXCacheViewer",
                onClick = onOpenRepository
            )
            AboutRow(
                title = stringResource(id = R.string.settings_about_developer),
                value = stringResource(id = R.string.settings_about_developer_value),
                onClick = onOpenDeveloper
            )
            AboutRow(
                title = stringResource(id = R.string.settings_about_license),
                onClick = onShowLicense
            )
            AboutRow(
                title = stringResource(id = R.string.settings_about_privacy),
                onClick = onShowPrivacy
            )
            AboutRow(
                title = stringResource(id = R.string.settings_about_disclaimer),
                onClick = onShowDisclaimer
            )
        }
    }
}

@Composable
private fun AboutRow(
    title: String,
    value: String? = null,
    onClick: (() -> Unit)? = null
) {
    val base = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = base
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onClick != null) {
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

private fun openUrl(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}.isSuccess
