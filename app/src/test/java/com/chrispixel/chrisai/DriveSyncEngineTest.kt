package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.drive.CloudCodec
import com.chrispixel.chrisai.data.drive.CloudEntry
import com.chrispixel.chrisai.data.drive.CloudMemoryItem
import com.chrispixel.chrisai.data.drive.ConflictStrategy
import com.chrispixel.chrisai.data.drive.DriveSyncEngine
import com.chrispixel.chrisai.data.drive.SyncAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSyncEngineTest {

    private fun entry(path: String, fp: String, mtime: Long = 0) = CloudEntry(path, fp, mtime)

    @Test
    fun `identical trees produce no actions`() {
        val f1 = entry("Memory.json", "abc", 1)
        val local = mapOf("Memory.json" to f1)
        val remote = mapOf("Memory.json" to entry("Memory.json", "abc", 2))
        assertTrue(DriveSyncEngine.plan(local, remote).isEmpty())
    }

    @Test
    fun `new remote file is downloaded only once`() {
        val local = emptyMap<String, CloudEntry>()
        val remote = mapOf("Conversations/conv_1.json" to entry("Conversations/conv_1.json", "x", 9))
        val plan = DriveSyncEngine.plan(local, remote)
        assertEquals(1, plan.size)
        assertTrue(plan.single() is SyncAction.Download)
    }

    @Test
    fun `local-only changes are uploaded`() {
        val local = mapOf("Memory.json" to entry("Memory.json", "changed", 5))
        val remote = mapOf("Memory.json" to entry("Memory.json", "old", 1))
        val plan = DriveSyncEngine.plan(local, remote)
        assertEquals(1, plan.size)
        assertTrue(plan.single() is SyncAction.Conflict) // fingerprint differs -> conflict
        val resolved = DriveSyncEngine.resolve(plan.single() as SyncAction.Conflict)
        assertTrue(resolved is SyncAction.Upload) // local mtime newer wins
    }

    @Test
    fun `conflict resolution keeps newest for conversations`() {
        val path = "Conversations/conv_9.json"
        val local = entry(path, "a", 10)
        val remote = entry(path, "b", 20)
        val conflict = SyncAction.Conflict(path, local, remote, ConflictStrategy.KEEP_NEWEST)
        val resolved = DriveSyncEngine.resolve(conflict)
        assertTrue(resolved is SyncAction.Download)
        assertEquals(path, resolved.path)
    }

    @Test
    fun `memory conflicts merge`() {
        val path = "Memory.json"
        val local = entry(path, "a", 10)
        val remote = entry(path, "b", 20)
        val conflict = SyncAction.Conflict(path, local, remote, DriveSyncEngine.strategyFor(path, local, remote))
        assertEquals(ConflictStrategy.MERGE, conflict.strategy)
        val resolved = DriveSyncEngine.resolve(conflict)
        assertTrue(resolved is SyncAction.Pending) // merged on the next online pass
    }

    @Test
    fun `going offline queues pending uploads only`() {
        val actions = listOf(
            SyncAction.Upload("Memory.json", entry("Memory.json", "a", 1)),
            SyncAction.Download("C.txt", entry("C.txt", "b", 2)),
            SyncAction.DeleteLocal("old.txt")
        )
        val pending = DriveSyncEngine.toPending(actions)
        assertTrue(pending.all { it is SyncAction.Pending })
        assertEquals(1, pending.size) // download/deletes are not re-queued
    }

    @Test
    fun `codec round trips memories with structure`() {
        val items = listOf(
            CloudMemoryItem(
                id = "m1",
                content = "El proyecto se llama ChrisTools.",
                category = "proyecto",
                importance = 5,
                tags = listOf("christools", "proyecto"),
                createdAtMillis = 1,
                updatedAtMillis = 2
            )
        )
        val json = CloudCodec.encodeMemory(items)
        val decoded = CloudCodec.decodeMemory(json)
        assertEquals(1, decoded.size)
        assertEquals("proyecto", decoded[0].category)
        assertEquals(5, decoded[0].importance)
        assertEquals(2, decoded[0].tags.size)
    }

    @Test
    fun `codec memory txt is readable`() {
        val txt = CloudCodec.encodeMemoryTxt(listOf(CloudMemoryItem("m1", "Una nota importante.")))
        assertTrue(txt.contains("Una nota importante."))
    }

    @Test
    fun `merge memory dedupes by id and keeps newest`() {
        val local = listOf(CloudMemoryItem("m1", "A", updatedAtMillis = 1))
        val remote = listOf(
            CloudMemoryItem("m1", "A (nuevo)", updatedAtMillis = 9),
            CloudMemoryItem("m2", "B", updatedAtMillis = 4)
        )
        val merged = CloudCodec.mergeMemory(local, remote)
        assertEquals(2, merged.size)
        assertEquals("A (nuevo)", merged.first { it.id == "m1" }.content)
    }

    @Test
    fun `conversation file names are sanitized`() {
        assertEquals("conversation_abc-01_2.json", CloudCodec.conversationFileName("abc-01/2"))
    }
}