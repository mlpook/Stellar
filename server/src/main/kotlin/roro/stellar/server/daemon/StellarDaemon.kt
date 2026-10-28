package roro.stellar.server.daemon

import android.util.Log

object StellarDaemon {
    private const val TAG = "StellarDaemon"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            return
        }

        var targetPid = args[0].toIntOrNull() ?: return
        val startCommand = args.drop(1).joinToString(" ")

        Log.i(TAG, "守护进程已启动，监控 stellar_server PID: $targetPid")

        while (true) {
            try {
                if (!isProcessAlive(targetPid)) {
                    Log.w(TAG, "检测到目标进程死亡，执行启动命令")
                    val newPid = spawnAndGetPid(startCommand)
                    if (newPid > 0) {
                        targetPid = newPid
                        Log.i(TAG, "已重启 stellar_server，新 PID: $newPid")
                    } else {
                        Log.e(TAG, "重启 stellar_server 失败，等待重试")
                    }
                }
                Thread.sleep(5000)
            } catch (e: Exception) {
                Log.e(TAG, "守护进程错误", e)
            }
        }
    }

    private fun spawnAndGetPid(startCommand: String): Int {
        return try {
            val base = startCommand.trim().removeSuffix("&").trim()
            val pb = ProcessBuilder("sh", "-c", "$base >/dev/null 2>&1 & echo \$!")
            pb.redirectErrorStream(true)
            pb.start().inputStream.bufferedReader().readLine()?.trim()?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "spawnAndGetPid 失败", e)
            -1
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            java.io.File("/proc/$pid").exists()
        } catch (_: Exception) {
            false
        }
    }
}
