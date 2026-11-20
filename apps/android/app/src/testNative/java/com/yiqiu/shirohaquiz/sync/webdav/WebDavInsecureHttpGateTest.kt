package com.yiqiu.shirohaquiz.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * test/webdav-clear 分支专用：验证「明文 HTTP 放行」是显式参数且默认拒绝。
 *
 * 与 WebDavClientTest.rejectsHttpAndUnsafePaths 互不替代：那条守默认拒绝的线保持不变。
 */
class WebDavInsecureHttpGateTest {

    /** 用例 1：显式放行时，明文 http 可用，且端口/结尾斜杠不被破坏。 */
    @Test
    fun allowInsecureHttpAcceptsPlainHttp() {
        val url = WebDavPaths.baseUrl("http://101.133.165.33:18080/", allowInsecureHttp = true)
        assertEquals("http", url.scheme)
        assertEquals(18080, url.port)
        assertTrue("应以 / 结尾: $url", url.toString().endsWith("/"))
    }

    /** 用例 2：默认（false）对同一地址必须拒绝，且消息指向「协议」。 */
    @Test
    fun defaultRejectsPlainHttpWithProtocolMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WebDavPaths.baseUrl("http://101.133.165.33:18080/")
        }
        val message = error.message.orEmpty()
        assertTrue("消息应指向协议: $message", message.contains("协议"))
    }

    /** 用例 3：放行 http 也不等于放行凭据——http 内嵌账号密码仍须拒，消息指向「凭据」。 */
    @Test
    fun allowInsecureHttpStillRejectsUserInfoOnPlainHttp() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WebDavPaths.baseUrl("http://u:p@dav.example.com/", allowInsecureHttp = true)
        }
        val message = error.message.orEmpty()
        assertTrue("消息应指向凭据: $message", message.contains("凭据"))
    }

    /**
     * 用例 4（承重用例）：allowInsecureHttp=true 且地址是 https 时，协议判据已经失效，
     * 只剩 userinfo 判据能拒绝。这条挂掉说明凭据防线其实是空的。
     */
    @Test
    fun allowInsecureHttpStillRejectsUserInfoOnHttps() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WebDavPaths.baseUrl("https://u:p@dav.example.com/", allowInsecureHttp = true)
        }
        val message = error.message.orEmpty()
        assertTrue("消息应指向凭据: $message", message.contains("凭据"))
    }
}
