package com.anton111111.haval.m6.keymapper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonRuleTest {
    @Test
    fun settingShortActionDisablesUnchangedLongAction() {
        val result = ButtonRule(0x21).withAction(false, KeyAction.MEDIA_NEXT)

        assertEquals(KeyAction.MEDIA_NEXT, result.shortAction)
        assertEquals(KeyAction.DISABLE, result.longAction)
    }

    @Test
    fun settingLongActionDisablesUnchangedShortAction() {
        val result = ButtonRule(0x21).withAction(true, KeyAction.MEDIA_PREVIOUS)

        assertEquals(KeyAction.DISABLE, result.shortAction)
        assertEquals(KeyAction.MEDIA_PREVIOUS, result.longAction)
    }

    @Test
    fun selectingDefaultOnOneGestureDoesNotRestoreStockForWholeButton() {
        val configured = ButtonRule(0x21).withAction(false, KeyAction.MEDIA_NEXT)
        val result = configured.withAction(false, KeyAction.DEFAULT)

        assertEquals(KeyAction.DISABLE, result.shortAction)
        assertEquals(KeyAction.DISABLE, result.longAction)
    }

    @Test
    fun selectingDefaultOnAnOverriddenButtonKeepsBothGesturesBlocked() {
        val configured = ButtonRule(0x21)
            .withAction(false, KeyAction.MEDIA_NEXT)
            .withAction(false, KeyAction.DEFAULT)

        assertEquals(KeyAction.DISABLE, configured.shortAction)
        assertEquals(KeyAction.DISABLE, configured.longAction)
        assertEquals(true, configured.overridesStock())
    }

    @Test
    fun selectingNonAppActionClearsSelectedAppPackage() {
        val configured = ButtonRule(0x21)
            .withAction(false, KeyAction.APP_LAUNCH)
            .withTargetApp(false, "com.example.music")
            .withAction(false, KeyAction.MEDIA_NEXT)

        assertNull(configured.shortAppPackage)
    }

    @Test
    fun selectingAppActionKeepsChosenPackage() {
        val configured = ButtonRule(0x21)
            .withAction(true, KeyAction.APP_LAUNCH)
            .withTargetApp(true, "com.example.music")

        assertEquals("com.example.music", configured.longAppPackage)
    }
}
