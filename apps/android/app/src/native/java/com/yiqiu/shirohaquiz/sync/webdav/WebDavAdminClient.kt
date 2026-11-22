package com.yiqiu.shirohaquiz.sync.webdav

import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 服务器侧账号管理接口（POST /admin/rotate）。
 * 鉴权用 HTTP Basic：add 用旧口令、finalize 用新口令，与服务端约定一致。
 * 口令只在本类内部拼接请求头，不写日志、不进异常消息。
 */
class WebDavAdminClient(
    baseUrl: String,
    callFactory: Call.Factory? = null
) {
    private val endpoint = WebDavRotation.endpoint(baseUrl)
    private val transport: Call.Factory = callFactory ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val detail: String)

    fun rotate(mode: String, username: String, oldPassword: String, newPassword: String): Result {
        val authPassword = if (mode == WebDavRotation.MODE_ADD) oldPassword else newPassword
        val body = WebDavRotation.body(mode, username, oldPassword, newPassword)
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON))
            .header("Authorization", Credentials.basic(username, authPassword, Charsets.UTF_8))
            .build()
        return try {
            transport.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                Result(ok = response.isSuccessful && text.contains("\"ok\": true")
                    || response.isSuccessful && text.contains("\"ok\":true"), detail = text.take(300))
            }
        } catch (error: IOException) {
            Result(ok = false, detail = "${error::class.java.simpleName}: ${error.message}")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
