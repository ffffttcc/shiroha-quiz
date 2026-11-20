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
 * test/webdav-clear 分支专用：验证明文放行真的贯穿到 store 层
 * （`save()` / `load()` / `credentials()`），而不只是 `WebDavPaths` 那一层。
 *
 * 默认边界两条都测：显式 true 才放行，显式 false 必须仍被拒。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDavSettingsStoreInsecureHttpTest {

    private val plainHttp = "http://101.133.165.33:18080/"

    /** 显式放行：save 不抛、load 拿回 http、credentials 不再抛（死结就在这里）。 */
    @Test
    fun allowInsecureHttpTrueLetsSaveLoadAndCredentialsUsePlainHttp() {
        val store = WebDavSettingsStore(RuntimeEnvironment.getApplication())

        val saved = store.save(
            WebDavSettings(baseUrl = plainHttp, username = "test"),
            password = "secret",
            allowInsecureHttp = true
        )
        assertTrue("save 应保留 http: ${saved.baseUrl}", saved.baseUrl.startsWith("http://"))

        val loaded = store.load()
        assertTrue("load 应仍是 http: ${loaded.baseUrl}", loaded.baseUrl.startsWith("http://"))

        val credentials = store.credentials(allowInsecureHttp = true)
        assertEquals("test", credentials.username)
    }

    /** 默认边界不破：显式 false 时同一 http 地址在 save() 仍必须被拒。 */
    @Test
    fun allowInsecureHttpFalseStillRejectsPlainHttpOnSave() {
        val store = WebDavSettingsStore(RuntimeEnvironment.getApplication())

        val error = assertThrows(IllegalArgumentException::class.java) {
            store.save(
                WebDavSettings(baseUrl = plainHttp, username = "test"),
                password = "secret",
                allowInsecureHttp = false
            )
        }
        val message = error.message.orEmpty()
        assertTrue("应指向协议: $message", message.contains("协议"))
    }
}
