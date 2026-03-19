package ua.com.myaiagent.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HttpFileLogger(context: Context) {

    private val file = File(context.filesDir, "ktor_http.log")
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun log(message: String) {
        val timestamp = sdf.format(Date())
        file.appendText("[$timestamp] $message\n")
        // Обрезаем если файл превысил 200 KB — оставляем последние 150 KB
        if (file.length() > 200_000) {
            val bytes = file.readBytes()
            file.writeBytes(bytes.takeLast(150_000).toByteArray())
        }
    }

    fun readLog(): String = if (file.exists() && file.length() > 0) file.readText() else "(лог пуст)"

    fun clear() = file.writeText("")
}
