package top.aico.wxcacheviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import top.aico.wxcacheviewer.ui.account.AccountSelectScreen
import top.aico.wxcacheviewer.ui.gallery.GalleryScreen
import top.aico.wxcacheviewer.viewmodel.AccountViewModel
import top.aico.wxcacheviewer.viewmodel.GalleryViewModel

@Composable
fun AppRoot(
    selectedAccountHash: String,
    onSelectedAccountChanged: (String) -> Unit
) {
    val accountViewModel: AccountViewModel = viewModel()
    val accountState by accountViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        accountViewModel.refresh()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (selectedAccountHash.isEmpty()) {
            AccountSelectScreen(
                state = accountState,
                onSelect = {
                    accountViewModel.select(it)
                    onSelectedAccountChanged(it)
                },
                onSaveRemark = accountViewModel::saveRemark,
                onRetry = accountViewModel::refresh
            )
        } else {
            val galleryViewModel: GalleryViewModel = viewModel(key = selectedAccountHash)
            val galleryState by galleryViewModel.uiState.collectAsState()

            LaunchedEffect(selectedAccountHash) {
                galleryViewModel.load(selectedAccountHash)
            }

            val remark = accountState.remarks[selectedAccountHash].orEmpty()
            GalleryScreen(
                state = galleryState,
                accountHash = selectedAccountHash,
                remark = remark,
                autoName = accountState.autoNames[selectedAccountHash].orEmpty(),
                accounts = accountState.accounts,
                remarks = accountState.remarks,
                autoNames = accountState.autoNames,
                canSwitchAccount = accountState.accounts.size > 1,
                onSetGridMode = galleryViewModel::setGridMode,
                onSetSortOption = galleryViewModel::setSortOption,
                onSwitchAccount = { onSelectedAccountChanged(it) },
                onSaveRemark = accountViewModel::saveRemark,
                onRefresh = { galleryViewModel.refresh(selectedAccountHash) },
                onStartSelection = { galleryViewModel.startSelection(it.name) },
                onToggleSelection = { galleryViewModel.toggleSelection(it.name) },
                onToggleSelectAll = galleryViewModel::toggleSelectAll,
                onInvertSelection = galleryViewModel::invertSelection,
                onExitSelection = galleryViewModel::exitSelection,
                onExportSelected = galleryViewModel::exportSelected,
                onDeleteSelected = galleryViewModel::deleteSelected,
                onNoticeShown = galleryViewModel::consumeNotice,
                onRetry = { galleryViewModel.load(selectedAccountHash) }
            )
        }
    }
}
