package com.yiqiu.shirohaquiz.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主线协议闸门：**一律只允许 https**，明文 http 在任何构建里都被拒。
 *
 * 历史：这个文件从 `test/webdav-clear` 试验分支带过来时，验的是「显式放行 flag 时 http 可用」。
 * 合入主线时 flag 已整条链路删除，因此原先 4 条用例按含义改写为下列断言，没有删文件。
 */
class WebDavInsecureHttpGateTest {

    /** 用例 1（对应原「allow=true 放行 http」）：明文 http 现在必须被拒。 */
    @Test
    fun plainHttpIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WebDavPaths.baseUrl("http://101.133.165.33:18080/")
        }
        val message = error.message.orEmpty()
        assertTrue("消息应指向协议/HTTPS：$message", message.contains("协议") || message.contains("HTTPS"))
    }

    /** 用例 2（对应原「默认拒绝」）：https 正常通过，且端口与结尾斜杠不被破坏。 */
    @Test
    fun httpsIsAccepted() {
        val url = WebDavPaths.baseUrl("https://101.133.165.33:18080/")
        assertEquals("https", url.scheme)
        assertEquals(18080, url.port)
        assertTrue("应以 / 结尾: $url", url.toString().endsWith("/"))
    }

    /** 用例 3（对应原「allow=true 时 http+userinfo 仍拒」）：http 已一律拒，凭据判据在 https 上仍生效。 */
    @Test
    fun userInfoOnHttpsIsStillRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WebDavPaths.baseUrl("https://u:p@dav.example.com/")
        }
        assertTrue("消息应指向凭据：${error.message}", error.message.orEmpty().contains("凭据"))
    }

    /** 用例 4（对应原承重用例）：isHttps 为真时协议判据放行，但凭据判据必须独立拦住。 */
    @Test
    fun httpsWithCredentialsIsBlockedByCredentialRule() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavPaths.baseUrl("https://user:secret@dav.example.com/dav/")
        }
        // 同样输入去掉 userinfo 就必须能过，证明拦的是凭据而不是把 https 也误伤
        val clean = WebDavPaths.baseUrl("https://dav.example.com/dav/")
        assertEquals("https", clean.scheme)
    }
}
