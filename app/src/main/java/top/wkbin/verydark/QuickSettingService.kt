package top.wkbin.verydark

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickSettingService : TileService() {

    // 磁贴运行在独立进程 :darkChange，不能用主进程的 Shizuku binder。
    // 所有对 reduce_bright_colors_* 的读写都走 SettingsControl（Shizuku/Root/本进程直写）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isDark = false

    override fun onClick() {
        super.onClick()
        // 无任何可用通道时直接提示，不翻转状态
        if (!SettingsControl.hasAnyChannel(application)) {
            Toast.makeText(application, "权限不足，请先在 App 内授权 Shizuku/Root", Toast.LENGTH_SHORT).show()
            return
        }

        val target = !isDark
        // 乐观更新：先翻转 UI，失败再回滚，避免点击无反应
        applyUiState(target)
        scope.launch {
            val result = SettingsControl.setActivated(application, target)
            if (result != SettingsControl.Result.OK) {
                // 回滚 UI 到真实状态
                val real = SettingsControl.isActivated(application)
                applyUiState(real)
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "切换失败，权限不足", Toast.LENGTH_SHORT).show()
                }
            } else {
                isDark = target
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!SettingsControl.hasAnyChannel(application)) {
            // 无通道：置灰，让用户明确看到不可用
            qsTile.state = Tile.STATE_UNAVAILABLE
            qsTile.updateTile()
            return
        }
        scope.launch {
            val real = SettingsControl.isActivated(application)
            isDark = real
            applyUiState(real)
        }
    }

    private fun applyUiState(dark: Boolean) {
        isDark = dark
        qsTile.state = if (dark) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
