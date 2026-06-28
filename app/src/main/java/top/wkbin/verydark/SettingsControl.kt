package top.wkbin.verydark

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 极暗模式设置的统一执行通道。
 *
 * 关键背景：
 * - APP 的 [QuickSettingService] 运行在独立进程 `:darkChange`，进程内既无 Shizuku binder
 *   连接、也无 Root su 通路，直接 [Settings.Secure.putInt] 写 `reduce_bright_colors_*`
 *   会因缺少 WRITE_SECURE_SETTINGS 而抛 SecurityException。
 * - 因此这里按「Shizuku newProcess（shell 身份）→ Root su → 本进程直写」的优先级
 *   执行 `settings` 命令。shell 用户天然可写 Settings.Secure，不依赖 APP 自身权限，
 *   是 Shizuku 授权方式下磁贴可用的最稳通路。
 */
object SettingsControl {

    private const val TAG = "SettingsControl"

    const val KEY_ACTIVATED = "reduce_bright_colors_activated"
    const val KEY_LEVEL = "reduce_bright_colors_level"

    enum class Result { OK, NO_PERMISSION }

    /**
     * 是否存在可用的执行通道（Shizuku 已授权 / Root / 本进程持权）。
     * 磁贴据此决定显示为可用还是置灰。
     */
    fun hasAnyChannel(context: Context): Boolean {
        return AuthHelper.isShizukuAuthorized() ||
            RootChecker.isDeviceRooted() ||
            AuthHelper.hasWriteSecureSettingsPermission(context)
    }

    /**
     * 写极暗开关。
     *
     * @return [Result.OK] 成功；[Result.NO_PERMISSION] 无任何可用执行通道。
     */
    suspend fun setActivated(context: Context, on: Boolean): Result =
        putInt(context, KEY_ACTIVATED, if (on) 1 else 0)

    /**
     * 写极暗亮度等级。UI 上 currentLight 是「剩余亮度」(0..100)，
     * 系统设置里 level 是「调暗程度」(0..100，越大越暗)，二者互补，故这里写 `100 - light`。
     */
    suspend fun setLevel(context: Context, light0to100: Int): Result =
        putInt(context, KEY_LEVEL, 100 - light0to100)

    /**
     * 读极暗开关状态。先尝试本进程直接读（已持权时最快），
     * 失败（@hide / 无权限）再回退到 Shizuku/Root 执行 `settings get`。
     */
    suspend fun isActivated(context: Context): Boolean = withContext(Dispatchers.IO) {
        // 读路径与写路径对称：优先走 shell `settings get`（shell 身份不受 @hide 限制，
        // 能读到真实值）。本进程直读降级为兜底——注意 S+ 上 reduce_bright_colors_activated
        // 是 @hide key，本进程 Settings.Secure.getInt 读不到真实值：带默认值的重载会
        // 静默返回默认值而不抛异常，从而吞掉"读不到"，导致磁贴状态读不回。
        val value = getInt(KEY_ACTIVATED, 0, context)
        value == 1
    }

    /**
     * 读一个 Settings.Secure 整数。优先级：Shizuku/Root 命令 → 本进程直读兜底。
     * 命令通道可用时优先用命令读（与写路径对称，不受 @hide 限制）。
     */
    private suspend fun getInt(key: String, defaultValue: Int, context: Context): Int =
        withContext(Dispatchers.IO) {
            // 1) 命令读（优先，与写路径对称）
            if (AuthHelper.isShizukuAuthorized()) {
                val r = AuthHelper.execShellViaShizuku("settings get secure $key")
                if (r.ok) {
                    val parsed = r.output.trim().toIntOrNull()
                    if (parsed != null) return@withContext parsed
                }
            }
            if (RootChecker.isDeviceRooted()) {
                val r = AuthHelper.execShellViaRoot("settings get secure $key")
                if (r.ok) {
                    val parsed = r.output.trim().toIntOrNull()
                    if (parsed != null) return@withContext parsed
                }
            }
            // 2) 本进程直读兜底（已持权且非 @hide 时可用）
            try {
                Settings.Secure.getInt(context.contentResolver, key, defaultValue)
            } catch (e: Exception) {
                Log.w(TAG, "读取 $key 全部失败，返回默认值 $defaultValue", e)
                defaultValue
            }
        }

    /**
     * 写一个 Settings.Secure 整数。优先级：Shizuku → Root → 本进程直写兜底。
     */
    private suspend fun putInt(context: Context, key: String, value: Int): Result =
        withContext(Dispatchers.IO) {
            // 1) Shizuku（shell 身份，最稳，不依赖 APP 自身权限）
            if (AuthHelper.isShizukuAuthorized()) {
                val r = AuthHelper.execShellViaShizuku("settings put secure $key $value")
                if (r.ok) return@withContext Result.OK
                Log.w(TAG, "Shizuku 写 $key 失败，退出码 ${r.exitCode}")
            }
            // 2) Root
            if (RootChecker.isDeviceRooted()) {
                val r = AuthHelper.execShellViaRoot("settings put secure $key $value")
                if (r.ok) return@withContext Result.OK
                Log.w(TAG, "Root 写 $key 失败，退出码 ${r.exitCode}")
            }
            // 3) 本进程直写兜底（已持 WRITE_SECURE_SETTINGS 时可用）
            try {
                Settings.Secure.putInt(context.contentResolver, key, value)
                Result.OK
            } catch (e: SecurityException) {
                Log.w(TAG, "本进程写 $key 被拒：${e.message}")
                Result.NO_PERMISSION
            } catch (e: Exception) {
                Log.w(TAG, "本进程写 $key 失败：${e.javaClass.simpleName} - ${e.message}")
                Result.NO_PERMISSION
            }
        }
}
