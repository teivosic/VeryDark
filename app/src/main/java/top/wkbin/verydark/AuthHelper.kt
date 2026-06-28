package top.wkbin.verydark

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuRemoteProcess
import java.io.DataOutputStream
import androidx.core.net.toUri

object AuthHelper {
    private const val TAG = "AuthHelper"

    /**
     * 检查是否有 WRITE_SECURE_SETTINGS 权限
     *
     * 只读探测：直接 checkSelfPermission，避免旧实现"先读再写回原值"带来的副作用
     * （旧实现在磁贴 onStartListening 等场景被反复调用时会触发无谓的 putInt 写入）。
     */
    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return try {
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS permission check failed: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /**
     * 通过 Shizuku 以 shell（uid 2000）身份执行一条命令，返回退出码与 stdout。
     *
     * shell 用户天然可写 Settings.Secure，因此即便 APP 自身未持有
     * WRITE_SECURE_SETTINGS，只要 Shizuku 已授权，就能用 `settings put` 写入。
     * 失败/不可用时返回 [ShellResult.failed]。
     */
    suspend fun execShellViaShizuku(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            if (!isShizukuAuthorized()) {
                Log.w(TAG, "Shizuku 未授权，无法执行命令")
                return@withContext ShellResult.failed()
            }
            val newProcessMethod = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as ShizukuRemoteProcess
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            ShellResult(exitCode, output)
        } catch (e: Exception) {
            Log.e(TAG, "通过 Shizuku 执行命令失败: $command", e)
            ShellResult.failed()
        }
    }

    /**
     * 通过 Root（su）执行一条命令，返回退出码与 stdout。不可用时返回 [ShellResult.failed]。
     */
    suspend fun execShellViaRoot(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            ShellResult(exitCode, output)
        } catch (e: Exception) {
            Log.e(TAG, "通过 Root 执行命令失败: $command", e)
            ShellResult.failed()
        }
    }

    /** shell 命令执行结果：退出码 + stdout。exitCode < 0 表示未能执行。 */
    data class ShellResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
        companion object {
            fun failed() = ShellResult(-1, "")
        }
    }
    
    /**
     * 检查 Shizuku 是否可用
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.v(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }
    
    /**
     * 检查 Shizuku 是否已授权
     */
    fun isShizukuAuthorized(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.v(TAG, "Shizuku permission check failed: ${e.message}")
            false
        }
    }
    
    /**
     * 请求 Shizuku 权限
     */
    fun requestShizukuPermission(context: Context, requestCode: Int) {
        try {
            if (!isShizukuAvailable()) {
                // 引导用户安装 Shizuku
                openShizukuInstallPage(context)
                return
            }
            
            if (isShizukuAuthorized()) {
                // 已经授权，直接尝试授予 WRITE_SECURE_SETTINGS
                CoroutineScope(Dispatchers.IO).launch {
                    grantWriteSecureSettingsViaShizuku(context)
                }
            } else {
                // 请求 Shizuku 权限
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求 Shizuku 权限失败", e)
        }
    }
    
    /**
     * 通过 Shizuku 授予 WRITE_SECURE_SETTINGS 权限
     *
     * 注意：WRITE_SECURE_SETTINGS 是一个特殊权限，需要通过 shell 命令 "pm grant" 来授予
     */
    suspend fun grantWriteSecureSettingsViaShizuku(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (hasWriteSecureSettingsPermission(context)) {
                Log.d(TAG, "已有 WRITE_SECURE_SETTINGS 权限，无需重复授权")
                return@withContext true
            }

            if (!isShizukuAuthorized()) {
                Log.e(TAG, "Shizuku 未授权，无法执行命令")
                return@withContext false
            }

            val command = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            val exitCode = execShellViaShizuku(command).exitCode

            if (exitCode == 0) {
                Thread.sleep(500)
                if (hasWriteSecureSettingsPermission(context)) {
                    Log.d(TAG, "通过 Shizuku 成功授予 WRITE_SECURE_SETTINGS 权限")
                    return@withContext true
                }
            }

            Log.w(TAG, "Shizuku 命令执行结束，但权限验证失败或退出码不为0: $exitCode")
            false
        } catch (e: Exception) {
            Log.e(TAG, "通过 Shizuku 授予权限失败", e)
            false
        }
    }
    
    /**
     * 检查是否有 Root 权限
     */
    suspend fun checkRootPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            Log.v(TAG, "Root not available: ${e.message}")
            false
        }
    }
    
    /**
     * 通过 Root 授予 WRITE_SECURE_SETTINGS 权限
     */
    suspend fun grantWriteSecureSettingsViaRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (hasWriteSecureSettingsPermission(context)) {
                Log.d(TAG, "已有 WRITE_SECURE_SETTINGS 权限，无需重复授权")
                return@withContext true
            }

            val command = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            val exitCode = execShellViaRoot(command).exitCode

            if (exitCode == 0) {
                Thread.sleep(500)
                if (hasWriteSecureSettingsPermission(context)) {
                    Log.d(TAG, "通过 Root 成功授予 WRITE_SECURE_SETTINGS 权限")
                    return@withContext true
                }
            }

            Log.w(TAG, "Root 命令执行结束，但权限验证失败或退出码不为0: $exitCode")
            false
        } catch (e: Exception) {
            Log.e(TAG, "通过 Root 授予权限失败", e)
            false
        }
    }
    
    /**
     * 获取 ADB 授权命令
     */
    fun getAdbGrantCommand(context: Context): String {
        val packageName = context.packageName
        return "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
    }
    
    /**
     * 复制文本到剪贴板
     */
    fun copyToClipboard(context: Context, text: String, label: String = "命令") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
    
    /**
     * 打开 Shizuku 安装页面
     */
    private fun openShizukuInstallPage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = "https://shizuku.rikka.app/".toUri()
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开 Shizuku 安装页面失败", e)
        }
    }
    
    /**
     * 打开 Shizuku 应用
     */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                context.startActivity(intent)
                true
            } else {
                openShizukuInstallPage(context)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开 Shizuku 应用失败", e)
            false
        }
    }
}
