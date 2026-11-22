package com.yiqiu.shirohaquiz.sync.webdav

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 上传鲁棒性：**拿到 2xx 不等于写成功**。
 *
 * 真机事故：2.8MB 的 bank blob 被中间层截断在 65536 字节（正好 64 KiB），
 * 服务器回 2xx、上游记 `Copied (new)`，本地解密才炸 `InvalidTag`。
 * 这组用例钉住三件事：上传后校验大小、半截先删再传、400/5xx 有限重试。
 */
class WebDavUploadRobustnessTest {

    private val objectName = "shiroha-sync-bank-" + "a".repeat(64) + ".enc"
    private val payload = ByteArray(4096) { (it % 251).toByte() }

    private fun client(fake: TruncatingDav) = WebDavClient(
        "https://dav.example.com/dav/",
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("user", "secret"),
        callFactory = fake,
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )

    /** 首次 PUT 只存 65536 字节（模拟 64 KiB 截断），之后正常。 */
    @Test
    fun truncatedUploadIsDetectedAndRetriedAfterDelete() {
        val fake = TruncatingDav(truncateFirstPuts = 1, putFailures = 0)
        client(fake).putSyncObject(objectName, payload)

        assertEquals("最终远端大小必须与本地密文一致", payload.size.toLong(), fake.storedSize(objectName))
        val putAttempts = fake.methods.count { it.first == "PUT" }
        assertTrue("应重试过（PUT 至少 2 次），实际 $putAttempts", putAttempts >= 2)
        assertTrue("半截对象应先被 DELETE，实际请求=${fake.methods}", fake.methods.any { it.first == "DELETE" })
    }

    /** 400 一次后成功：不应影响整轮。 */
    @Test
    fun badRequestIsRetriedAndEventuallySucceeds() {
        val fake = TruncatingDav(truncateFirstPuts = 0, putFailures = 1)
        client(fake).putSyncObject(objectName, payload)

        assertEquals(payload.size.toLong(), fake.storedSize(objectName))
        assertEquals("400 后应重试一次成功", 2, fake.methods.count { it.first == "PUT" })
    }

    /** 持续 400：重试用尽后抛出，且不会无限重试。 */
    @Test
    fun persistentBadRequestGivesUpAfterThreeAttempts() {
        val fake = TruncatingDav(truncateFirstPuts = 0, putFailures = 99)
        val error = assertThrows(WebDavException::class.java) {
            client(fake).putSyncObject(objectName, payload)
        }
        assertEquals("最多尝试 3 次", 3, fake.methods.count { it.first == "PUT" })
        assertTrue("错误信息应可诊断：${error.message}", error.message.orEmpty().isNotEmpty())
    }

    /** 非可重试错误（如 401）应立即抛出，不做无意义重试。 */
    @Test
    fun nonRetryableStatusCodeIsNotRetried() {
        val fake = TruncatingDav(truncateFirstPuts = 0, putFailures = 1, failureCode = 401)
        assertThrows(WebDavException::class.java) {
            client(fake).putSyncObject(objectName, payload)
        }
        assertEquals("鉴权失败不该重试", 1, fake.methods.count { it.first == "PUT" })
    }

    /**
     * 极简 WebDAV 桩：支持 PUT / GET / DELETE / PROPFIND（statFile 用），
     * 可按次数模拟「截断写入」与「PUT 失败」。
     */
    internal class TruncatingDav(
        private val truncateFirstPuts: Int,
        private val putFailures: Int,
        private val failureCode: Int = 400
    ) : Call.Factory {
        val methods = mutableListOf<Pair<String, String>>()
        private val files = linkedMapOf<String, ByteArray>()
        private var putCount = 0
        private var truncated = 0

        fun storedSize(name: String): Long? {
            val path = "/ShirohaQuiz/Backups/$name"
            return files[path]?.size?.toLong()
        }

        override fun newCall(request: Request): Call = FakeCall(request) { current ->
            methods += current.method to current.url.encodedPath
            val path = current.url.encodedPath
            when (current.method) {
                "PUT" -> {
                    putCount++
                    if (putCount <= putFailures) {
                        response(current, failureCode, ByteArray(0))
                    } else {
                        val body = Buffer().also { current.body!!.writeTo(it) }.readByteArray()
                        val store = if (truncated < truncateFirstPuts) {
                            truncated++
                            body.copyOf(minOf(65536, body.size))
                        } else {
                            body
                        }
                        files[path] = store
                        response(current, 201, ByteArray(0))
                    }
                }
                "GET" -> files[path]?.let { response(current, 200, it) } ?: response(current, 404, ByteArray(0))
                "DELETE" -> { files.remove(path); response(current, 204, ByteArray(0)) }
                "PROPFIND" -> {
                    val data = files[path]
                    val type = if (path.endsWith('/')) "<d:collection/>" else ""
                    if (data == null && type.isEmpty()) response(current, 404, ByteArray(0))
                    else response(
                        current, 207,
                        ("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$path</d:href>" +
                            "<d:propstat><d:prop><d:resourcetype>$type</d:resourcetype>" +
                            "<d:getcontentlength>${data?.size ?: 0}</d:getcontentlength>" +
                            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
                            ).toByteArray()
                    )
                }
                else -> response(current, 405, ByteArray(0))
            }
        }

        private class FakeCall(private val original: Request, private val handle: (Request) -> Response) : Call {
            private var cancelled = false
            override fun request() = original
            override fun execute(): Response {
                if (cancelled) throw IOException("Cancelled")
                return handle(original)
            }
            override fun enqueue(responseCallback: Callback) = error("Only blocking calls are supported.")
            override fun cancel() { cancelled = true }
            override fun isExecuted() = false
            override fun isCanceled() = cancelled
            override fun timeout() = Timeout()
            override fun clone(): Call = FakeCall(original, handle)
        }

        companion object {
            private fun response(request: Request, code: Int, bytes: ByteArray): Response = Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(bytes.toResponseBody()).build()
        }
    }
}
