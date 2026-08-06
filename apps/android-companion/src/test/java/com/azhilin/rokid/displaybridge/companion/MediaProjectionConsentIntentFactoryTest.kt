package com.azhilin.rokid.displaybridge.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProjectionConsentIntentFactoryTest {
    @Test fun `api 34 and newer prefer whole default display consent`() {
        assertEquals(ProjectionConsentScope.SYSTEM_CHOOSER, MediaProjectionConsentIntentFactory.scopeForSdk(33))
        assertEquals(ProjectionConsentScope.WHOLE_DEFAULT_DISPLAY, MediaProjectionConsentIntentFactory.scopeForSdk(34))
        assertEquals(ProjectionConsentScope.WHOLE_DEFAULT_DISPLAY, MediaProjectionConsentIntentFactory.scopeForSdk(36))
    }
}
