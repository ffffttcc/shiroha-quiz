package com.yiqiu.shirohaquiz.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `WebDavSettingsStore` 层的协议闸门：主线只允许 https，且恢复「必须有密码」的主线语义。
 *
 * 历史：从 `test/webdav-clear` 带过来时，这里验的是「allow=true 时 http 也能 save/load/credentials」，
 * 以及「无密码时回落 test-only 占位口令」。两处都是试验专用，flag 与占位口令已一并删除，
 * 用例按含义改写，没有删文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDavSettingsStoreInsecureHttpTest {

    private val httpsUrl = "https://101.133.165.33:18080/"

    /** 用例 1（对应原「allow=true 时 http 可 save」）：现在 http 在 save 阶段就被拒。 */
    @Test
    fun plainHttpSettingsAreRejectedOnSave() {
        val store = WebDavSettingsStore(RuntimeEnvironment.getApplication())
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.save(WebDavSettings(baseUrl = "http://101.133.165.33:18080/", username = "test"), password = "secret")
        }
        val message = error.message.orEmpty()
        assertTrue("消息应指向协议/HTTPS：$message", message.contains("协议") || message.contains("HTTPS"))
    }

    /** 用例 2（对应原「放行时 save/load/credentials 走通」）：https 全链仍然可用。 */
    @Test
    fun httpsSettingsRoundTripAndCredentialsWork() {
        val store = WebDavSettingsStore(RuntimeEnvironment.getApplication())
        val saved = store.save(WebDavSettings(baseUrl = httpsUrl, username = "test"), password = "secret")
        assertTrue("save 应保留 https：${saved.baseUrl}", saved.baseUrl.startsWith("https://"))

        val loaded = store.load()
        assertTrue("load 应仍是 https：${loaded.baseUrl}", loaded.baseUrl.startsWith("https://"))

        val credentials = store.credentials()
        assertEquals("test", credentials.username)
    }

    /**
     * 用例 3（对应原「无密码回落 test-only 占位口令」）：
     * 主线语义已恢复为「没有密码就抛 WebDavException」，绝不再用占位口令冒充真实凭据。
     */
    @Test
    fun credentialsWithoutPasswordThrowsInsteadOfFallingBack() {
        val store = WebDavSettingsStore(RuntimeEnvironment.getApplication())
        store.save(WebDavSettings(baseUrl = httpsUrl, username = "test", rememberPassword = false), password = null)
        val error = assertThrows(WebDavException::class.java) { store.credentials() }
        assertTrue("应提示缺少密码：${error.message}", error.message.orEmpty().contains("密码"))
    }
}
