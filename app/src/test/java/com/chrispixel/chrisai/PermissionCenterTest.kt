package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.permissions.CapabilityId
import com.chrispixel.chrisai.data.permissions.PermissionCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCenterTest {

    @Test
    fun `snapshot covers every capability`() {
        val snapshot = PermissionCenter.snapshot(
            micGranted = true,
            cameraGranted = true,
            screenShareActive = true,
            notificationsEnabled = true,
            toolsEnabled = true,
            driveConnected = true,
            visionAvailable = true,
            fallbackProvider = true
        )
        assertEquals(CapabilityId.entries.size, snapshot.size)
        assertEquals(CapabilityId.entries.toSet(), snapshot.map { it.id }.toSet())
        assertTrue(snapshot.all { it.enabled })
    }

    @Test
    fun `denied state reflects reality`() {
        val snapshot = PermissionCenter.snapshot(
            micGranted = false,
            cameraGranted = false,
            screenShareActive = false,
            notificationsEnabled = false,
            toolsEnabled = false,
            driveConnected = false,
            visionAvailable = false,
            fallbackProvider = false
        )
        assertFalse(snapshot.all { it.enabled })
        assertEquals(
            "Requiere permiso",
            snapshot.first { it.id == CapabilityId.MIC }.detail
        )
        assertEquals(
            "No conectado",
            snapshot.first { it.id == CapabilityId.DRIVE }.detail
        )
        assertEquals(
            "OpenRouter",
            snapshot.first { it.id == CapabilityId.PROVIDER }.detail
        )
    }

    @Test
    fun `groups are stable and cover all ids`() {
        val seen = CapabilityId.entries.associateWith { PermissionCenter.group(it) }
        assertEquals("Voz y visión", seen.getValue(CapabilityId.MIC))
        assertEquals("Voz y visión", seen.getValue(CapabilityId.CAMERA))
        assertEquals("Voz y visión", seen.getValue(CapabilityId.SCREEN))
        assertEquals("Voz y visión", seen.getValue(CapabilityId.NOTIFICATIONS))
        assertEquals("Motor", seen.getValue(CapabilityId.VISION))
        assertEquals("Motor", seen.getValue(CapabilityId.PROVIDER))
        assertEquals("ChrisTools", seen.getValue(CapabilityId.TOOLS))
        assertEquals("ChrisTools", seen.getValue(CapabilityId.DRIVE))
    }
}