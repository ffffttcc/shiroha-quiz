package com.yiqiu.shirohaquiz.sync.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * `WebDavClient.request()` 的两条判据：协议必须是 https、目标必须同源。
 *
 * 历史：从 `test/webdav-clear` 带过来时，这里验的是「显式放行 flag 时 http 同源可通过」。
 * 合入主线后 flag 已删除，原「放行」用例改写为「构造阶段即拒绝」，同源用例（反射）保留原强度。
 */
class WebDavRequestGuardTest {

    private fun client(url: String) = WebDavClient(
        url,
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("test", "test-only-password"),
        callFactory = WebDavClientTest.FakeDav(),
        xmlParserFactory = { org.kxml2.io.KXmlParser() }
    )

    /** 用例 1（对应原「allow=true 时 http 同源可通过」）：现在构造阶段就拒绝明文 http。 */
    @Test
    fun plainHttpIsRejectedAtConstruction() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            client("http://101.133.165.33:18080/")
        }
        assertTrue("消息应指向协议：${error.message}", error.message.orEmpty().contains("协议"))
    }

    /** 用例 2（对应原「默认拒绝」）：https 同源可以正常发出 PROPFIND。 */
    @Test
    fun httpsSameOriginWorks() {
        val resource = client("https://dav.example.com/dav/").testConnection()
        assertNotNull(resource)
    }

    /**
     * 用例 3（对应原「跨源必须抛」）：放行协议不等于放行跨源，request() 的同源判据必须仍生效。
     * request() 是 private 且无公开入口能传外部 URL，故用反射直达该判据。
     */
    @Test
    fun crossOriginRequestIsStillRejected() {
        val target = client("https://dav.example.com/dav/")
        val method = WebDavClient::class.java.getDeclaredMethod(
            "request",
            HttpUrl::class.java,
            String::class.java,
            RequestBody::class.java
        ).apply { isAccessible = true }

        val thrown = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                target,
                "https://cross.example.com/dav/".toHttpUrl(),
                "PROPFIND",
                null
            )
        }
        val cause = thrown.cause
        assertTrue("应抛 IllegalArgumentException，实际: $cause", cause is IllegalArgumentException)
        val message = cause?.message.orEmpty()
        assertTrue("消息应指向越界/同源，实际: $message", message.contains("越界") || message.contains("同源"))
    }
}
