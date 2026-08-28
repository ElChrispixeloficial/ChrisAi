package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.update.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `parses clean versions`() {
        assertEquals(SemanticVersion(0, 5, 0), SemanticVersion.parse("0.5.0"))
        assertEquals(SemanticVersion(0, 5, 1), SemanticVersion.parse("v0.5.1"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("V1.2.3"))
        assertEquals(SemanticVersion(0, 10, 0), SemanticVersion.parse("0.10.0"))
    }

    @Test
    fun `parses suffixes ignoring them`() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3-rc1"))
        assertEquals(SemanticVersion(0, 5, 0), SemanticVersion.parse("v0.5.0+build7"))
    }

    @Test
    fun `rejects malformed versions`() {
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("0.5"))
        assertNull(SemanticVersion.parse("abc"))
        assertNull(SemanticVersion.parse("0.5.a"))
        assertNull(SemanticVersion.parse("v"))
    }

    @Test
    fun `isNewer decides correctly`() {
        assertTrue(SemanticVersion.isNewer("0.5.0", "0.5.1"))
        assertTrue(SemanticVersion.isNewer("0.9.0", "0.10.0"))
        assertTrue(SemanticVersion.isNewer("1.0.0", "1.1.0"))
        assertFalse(SemanticVersion.isNewer("0.5.0", "0.5.0"))
        assertFalse(SemanticVersion.isNewer("0.5.1", "0.5.0"))
        assertFalse(SemanticVersion.isNewer("0.5.0", "0.4.9"))
        assertFalse(SemanticVersion.isNewer("0.5.0", "not-a-version"))
    }

    @Test
    fun `compareTo orders by parts`() {
        assertTrue(SemanticVersion(0, 9, 9) < SemanticVersion(0, 10, 0))
        assertTrue(SemanticVersion(0, 5, 0) < SemanticVersion(0, 5, 1))
        assertEquals(0, SemanticVersion(0, 5, 0).compareTo(SemanticVersion(0, 5, 0)))
    }
}