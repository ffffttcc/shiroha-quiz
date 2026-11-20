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
 * test/webdav-clear 收官用例：证明「明文放行」只在 allowInsecureHttp=true 时生效，
 * 且同源判据没有被一起放开（这是最后一堵墙 `WebDavClient.request()` 的两条判据）。
 */
class WebDavRequestGuardTest {

    private fun client(url: String, allowInsecureHttp: Boolean) = WebDavClient(
        url,
        "/ShirohaQuiz/Backups/",
        WebDavCredentials("test", "test-only"),
        callFactory = WebDavClientTest.FakeDav(),
        xmlParserFactory = { org.kxml2.io.KXmlParser() },
        allowInsecureHttp = allowInsecureHttp
    )

    /** 放行时：http + 同源 应能真正发出 PROPFIND（不再被 require 拦下）。 */
    @Test
    fun allowInsecureHttpTrueLetsSameOriginPlainHttpThrough() {
        val resource = client("http://101.133.165.33:18080/", allowInsecureHttp = true).testConnection()
        assertNotNull(resource)
    }

    /** 默认拒绝：同一 http 地址在构造阶段就应被协议判据拦下，且消息指明协议。 */
    @Test
    fun allowInsecureHttpFalseRejectsPlainHttpWithProtocolMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            client("http://101.133.165.33:18080/", allowInsecureHttp = false)
        }
        assertTrue("消息应指向协议: ${error.message}", error.message.orEmpty().contains("协议"))
    }

    /**
     * 放行 http 不等于放行跨源：request() 的同源判据必须仍然生效。
     * request() 是 private 且无公开入口能传入外部 URL，故用反射直达该判据。
     */
    @Test
    fun allowInsecureHttpTrueStillRejectsCrossOriginRequest() {
        val target = client("http://101.133.165.33:18080/", allowInsecureHttp = true)
        val method = WebDavClient::class.java.getDeclaredMethod(
            "request",
            HttpUrl::class.java,
            String::class.java,
            RequestBody::class.java
        ).apply { isAccessible = true }

        val thrown = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                target,
                "http://cross.example.com:18080/ShirohaQuiz/Backups/".toHttpUrl(),
                "PROPFIND",
                null
            )
        }
        val cause = thrown.cause
        assertTrue("应抛 IllegalArgumentException，实际: $cause", cause is IllegalArgumentException)
        val message = cause?.message.orEmpty()
        assertTrue(
            "消息应指向越界/同源，实际: $message",
            message.contains("越界") || message.contains("同源")
        )
    }
}
