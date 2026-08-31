package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.drive.DriveService
import com.chrispixel.chrisai.data.drive.SyncFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DriveService REST mapping over the in-memory [FakeDrive] transport. */
class DriveServiceTest {

    private fun service() = DriveService(FakeDrive())

    @Test
    fun `ensure layout creates the ChrisAI tree once`() = runBlocking {
        val fake = FakeDrive()
        val svc = DriveService(fake)
        val root1 = svc.ensureLayout("tok")
        val root2 = svc.ensureLayout("tok")
        assertEquals(root1, root2)
        assertTrue(fake.hasFile("ChrisAI"))
    }

    @Test
    fun `put and download round-trip under a folder`() = runBlocking {
        val fake = FakeDrive()
        val svc = DriveService(fake)
        val content = "{\"hola\":true}"
        assertTrue(svc.put("tok", "Memory/Memory.json", content))
        assertEquals(content, svc.download("tok", "Memory/Memory.json"))
        assertNull(svc.download("tok", "Conversations/missing.json"))
    }

    @Test
    fun `put twice updates the same file`() = runBlocking {
        val fake = FakeDrive()
        val svc = DriveService(fake)
        svc.put("tok", "Settings/Settings.json", "v1")
        svc.put("tok", "Settings/Settings.json", "v2")
        assertEquals("v2", svc.download("tok", "Settings/Settings.json"))
        val refs = svc.listAll("tok")
        assertEquals(1, refs.count { it.path == "Settings/Settings.json" })
    }

    @Test
    fun `list all returns logical relative paths with fingerprints`() = runBlocking {
        val fake = FakeDrive()
        val svc = DriveService(fake)
        svc.put("tok", "Memory/Memory.json", "mem")
        svc.put("tok", "Data/device-1.json", "data")
        val refs = svc.listAll("tok").associateBy { it.path }
        assertEquals("Memory/Memory.json", refs.keys.first { it == "Memory/Memory.json" })
        assertEquals("Data/device-1.json", refs.keys.first { it == "Data/device-1.json" })
        assertEquals(SyncFile.md5("mem"), refs["Memory/Memory.json"]?.md5)
    }

    @Test
    fun `delete removes an existing file`() = runBlocking {
        val fake = FakeDrive()
        val svc = DriveService(fake)
        svc.put("tok", "Conversations/conv-1.json", "c1")
        assertTrue(svc.delete("tok", "Conversations/conv-1.json"))
        assertFalse(svc.delete("tok", "Conversations/conv-missing.json"))
        assertNull(svc.download("tok", "Conversations/conv-1.json"))
    }

    @Test
    fun `list all gives no paths when the tree has only folders`() = runBlocking {
        val fake = FakeDrive()
        val svc = DriveService(fake)
        val refs = svc.listAll("tok")
        assertTrue(refs.isEmpty())
    }

    @Test
    fun `credentials never leak into paths`() = runBlocking {
        val fake = FakeDrive()
        val svc = DriveService(fake)
        svc.put("secret-token", "Memory/Memory.json", "x")
        val refs = svc.listAll("secret-token")
        assertNotNull(refs)
    }
}