package com.yiqiu.shirohaquiz.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavRotationTest {
    @Test fun passwordUnchangedDoesNotTriggerRotation() {
        assertFalse(WebDavRotation.needed("shiroha", "pw-old", "shiroha", "pw-old"))
    }

    @Test fun passwordChangeTriggersRotation() {
        assertTrue(WebDavRotation.needed("shiroha", "pw-old", "shiroha", "pw-new"))
    }

    @Test fun usernameChangeAlsoTriggersRotationBecauseUsernameIsKeyMaterial() {
        assertTrue(WebDavRotation.needed("shiroha", "pw", "shiroha2", "pw"))
    }

    @Test fun bodyCarriesModeAndBothPasswordsAndEscapesQuotes() {
        val body = WebDavRotation.body(WebDavRotation.MODE_ADD, "shiroha", "old\"pw", "new\\pw")
        assertTrue(body.contains("\"mode\":\"add\""))
        assertTrue(body.contains("\"username\":\"shiroha\""))
        assertTrue(body.contains("\"oldPassword\":\"old\\\"pw\""))
        assertTrue(body.contains("\"newPassword\":\"new\\\\pw\""))
    }

    @Test fun endpointToleratesTrailingSlash() {
        assertEquals("https://dev.ftc27.xyz/admin/rotate", WebDavRotation.endpoint("https://dev.ftc27.xyz/"))
        assertEquals("https://dev.ftc27.xyz/admin/rotate", WebDavRotation.endpoint("https://dev.ftc27.xyz"))
    }

    @Test fun finalizeIsBlockedWhenAddOrResyncFailedSoOldPasswordIsNeverDropped() {
        assertFalse(WebDavRotation.canFinalize(addSucceeded = false, resyncSucceeded = true))
        assertFalse(WebDavRotation.canFinalize(addSucceeded = true, resyncSucceeded = false))
        assertTrue(WebDavRotation.canFinalize(addSucceeded = true, resyncSucceeded = true))
    }
}
