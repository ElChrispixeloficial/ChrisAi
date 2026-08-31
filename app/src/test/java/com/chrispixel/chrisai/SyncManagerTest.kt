package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.drive.CloudFileStore
import com.chrispixel.chrisai.data.drive.CloudCodec
import com.chrispixel.chrisai.data.drive.DriveService
import com.chrispixel.chrisai.data.drive.SyncFile
import com.chrispixel.chrisai.data.drive.SyncManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

/** In-memory [CloudFileStore] for sync orchestration tests. */
internal class FakeCloudFiles(initial: Map<String, String>) : CloudFileStore {

    private val local = HashMap(initial)
    private val tombstones = mutableSetOf<String>()
    val applied = mutableListOf<SyncFile>()
    val deleted = mutableListOf<String>()

    override suspend fun localFiles(): List<SyncFile> =
        local.map { SyncFile(it.key, it.value) }

    override suspend fun applyRemote(files: List<SyncFile>) {
        applied.addAll(files)
        files.forEach { local[it.path] = it.content }
    }

    override suspend fun deleteLocal(path: String) {
        deleted.add(path)
        if (local.remove(path) != null) tombstones.add(path)
    }

    override suspend fun deletedPaths(): List<String> = tombstones.toList()

    fun content(path: String): String? = local[path]
}

class SyncManagerTest {

    private fun manager(initial: Map<String, String>): Pair<SyncManager, FakeCloudFiles> {
        val files = FakeCloudFiles(initial)
        val manager = SyncManager(DriveService(FakeDrive()), files)
        return manager to files
    }

    @Test
    fun `uploads local files when the cloud is empty`() = runBlocking {
        val (manager, files) = manager(
            mapOf(
                CloudCodec.memoryPath() to "mem local",
                CloudCodec.settingsPath() to "settings"
            )
        )
        val result = manager.sync("tok")
        assertTrue(result.uploaded.contains(CloudCodec.memoryPath()))
        assertTrue(result.uploaded.contains(CloudCodec.settingsPath()))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `downloads remote files when the device is empty`() = runBlocking {
        val fake = FakeDrive()
        val files = FakeCloudFiles(emptyMap())
        val manager = SyncManager(DriveService(fake), files)
        driveSeed(fake, CloudCodec.memoryPath(), "mem remote")
        driveSeed(fake, CloudCodec.conversationPath("abc"), "conv remote")

        val result = manager.sync("tok")
        assertTrue(result.downloaded.contains(CloudCodec.memoryPath()))
        assertTrue(result.downloaded.contains(CloudCodec.conversationPath("abc")))
        assertEquals("mem remote", files.content(CloudCodec.memoryPath()))
        assertEquals(2, files.applied.size)
    }

    @Test
    fun `no-op when local and remote match`() = runBlocking {
        val fake = FakeDrive()
        driveSeed(fake, CloudCodec.memoryPath(), "mem same")
        val files = FakeCloudFiles(mapOf(CloudCodec.memoryPath() to "mem same"))
        val result = SyncManager(DriveService(fake), files).sync("tok")
        assertTrue(result.uploaded.isEmpty())
        assertTrue(result.downloaded.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `legacy root memory json maps to the foldered path`() = runBlocking {
        val fake = FakeDrive()
        driveSeed(fake, CloudCodec.MEMORY_FILE, "old layout memory") // root-level
        val files = FakeCloudFiles(emptyMap())
        val result = SyncManager(DriveService(fake), files).sync("tok")
        assertTrue(result.downloaded.contains(CloudCodec.memoryPath()))
        assertTrue(result.downloaded.none { it == CloudCodec.MEMORY_FILE })
        assertEquals("old layout memory", files.content(CloudCodec.memoryPath()))
    }

    @Test
    fun `remote file deleted locally is cleaned on the cloud`() = runBlocking {
        val fake = FakeDrive()
        driveSeed(fake, CloudCodec.conversationPath("gone"), "conv")
        val files = FakeCloudFiles(
            mapOf(
                CloudCodec.settingsPath() to "s",
                CloudCodec.conversationPath("gone") to "conv"
            )
        )
        files.deleteLocal(CloudCodec.conversationPath("gone")) // user deleted it
        val result = SyncManager(DriveService(fake), files).sync("tok")
        assertTrue(result.deletedRemotes.contains(CloudCodec.conversationPath("gone")))
        assertTrue(result.deletedLocals.contains(CloudCodec.conversationPath("gone")))
    }

    @Test
    fun `conflict keeps the newest copy and reports it`() = runBlocking {
        val fake = FakeDrive()
        driveSeed(fake, CloudCodec.memoryPath(), "remote version")
        val files = FakeCloudFiles(mapOf(CloudCodec.memoryPath() to "local version"))
        val result = SyncManager(DriveService(fake), files).sync("tok")
        // Same mtime => local kept (KEEP_NEWEST tie goes to local).
        assertTrue(result.conflictsKeptLocal.contains(CloudCodec.memoryPath()))
        assertEquals("local version", files.content(CloudCodec.memoryPath()))
    }

    @Test
    fun `local file removed from device deletes it locally`() = runBlocking {
        val fake = FakeDrive()
        driveSeed(fake, CloudCodec.conversationPath("gone"), "conv")
        val files = FakeCloudFiles(
            mapOf(
                CloudCodec.conversationPath("gone") to "conv",
                CloudCodec.memoryPath() to "m"
            )
        )
        files.deleteLocal(CloudCodec.conversationPath("gone")) // user deleted it
        val result = SyncManager(DriveService(fake), files).sync("tok")
        assertTrue(result.deletedRemotes.contains(CloudCodec.conversationPath("gone")))
        assertTrue(result.deletedLocals.isNotEmpty())
    }

    /** Seeds a file into the fake cloud giving it the exact fingerprint. */
    private suspend fun driveSeed(fake: FakeDrive, path: String, content: String) {
        if ('/' in path) {
            val svc = DriveService(fake)
            svc.put("seed", path, content)
        } else {
            fake.seedRoot(path, content) // legacy root-level file
        }
    }

    @Test
    fun `error does not crash the whole sync`() = runBlocking {
        val failing = DriveService(object : com.chrispixel.chrisai.data.drive.DriveHttpClient {
            override suspend fun request(
                method: String,
                url: String,
                token: String,
                body: String?
            ) = com.chrispixel.chrisai.data.drive.DriveHttpResponse(401, "{}")
        })
        val files = FakeCloudFiles(mapOf(CloudCodec.memoryPath() to "m"))
        val result = SyncManager(failing, files).sync("tok")
        assertTrue(result.errors.isNotEmpty())
        assertNotNull(result)
    }
}