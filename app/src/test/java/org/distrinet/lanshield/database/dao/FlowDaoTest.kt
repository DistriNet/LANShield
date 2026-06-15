package org.distrinet.lanshield.database.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.model.LANFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FlowDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FlowDao

    private val remote = InetSocketAddress(InetAddress.getByName("8.8.8.8"), 443)
    private val local = InetSocketAddress(InetAddress.getByName("192.168.1.2"), 40000)

    private fun makeFlow(
        appId: String,
        timeEnd: Long,
        ingress: Long = 0,
        egress: Long = 0,
        timeEndAtLastSync: Long = 0,
        scheduledForDeletion: Boolean = false,
    ): LANFlow = LANFlow.createFlow(appId, remote, local, "TCP", Policy.ALLOW).copy(
        timeEnd = timeEnd,
        dataIngress = ingress,
        dataEgress = egress,
        timeEndAtLastSync = timeEndAtLastSync,
        scheduledForDeletion = scheduledForDeletion,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.FlowDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `countNotSyncedFlows counts only flows whose timeEnd advanced past last sync`() {
        dao.insertFlow(makeFlow("a", timeEnd = 100, timeEndAtLastSync = 0))   // not synced
        dao.insertFlow(makeFlow("b", timeEnd = 100, timeEndAtLastSync = 100)) // synced (equal)
        dao.insertFlow(makeFlow("c", timeEnd = 100, timeEndAtLastSync = 50))  // not synced

        assertThat(dao.countNotSyncedFlows()).isEqualTo(2)
    }

    @Test
    fun `getNotSyncedFlowsAfter pages by uuid without gaps or duplicates`() {
        val inserted = (1..5).map { makeFlow("app", timeEnd = 100L + it) }
        inserted.forEach { dao.insertFlow(it) }

        val pageSize = 2
        var cursor = UUID(0L, 0L)
        val collected = mutableListOf<LANFlow>()
        while (true) {
            val page = dao.getNotSyncedFlowsAfter(cursor, pageSize)
            if (page.isEmpty()) break
            assertThat(page.size).isAtMost(pageSize)
            collected += page
            cursor = page.last().uuid
        }

        // keyset pagination must return every flow exactly once (no gaps, no duplicates).
        // Note: the query orders by the uuid BLOB (SQLite unsigned byte order), which differs
        // from java.util.UUID's signed-long compareTo, so we assert completeness, not Java order.
        assertThat(collected.map { it.uuid }).containsExactlyElementsIn(inserted.map { it.uuid })
        assertThat(collected.map { it.uuid }.toSet()).hasSize(inserted.size)
    }

    @Test
    fun `getFlowAverages aggregates totals and applies the 24h cutoff`() = runBlocking {
        val now = 10_000_000L
        val cutoff = now - 1_000L
        dao.insertFlow(makeFlow("a", timeEnd = now, ingress = 100, egress = 10))        // within 24h
        dao.insertFlow(makeFlow("a", timeEnd = cutoff - 1, ingress = 5, egress = 1))    // older
        dao.insertFlow(makeFlow("b", timeEnd = now, ingress = 7, egress = 3))

        val averages = dao.getFlowAverages(cutoff).first()
        val a = averages.single { it.appId == "a" }
        assertThat(a.totalBytesIngress).isEqualTo(105)
        assertThat(a.totalBytesEgress).isEqualTo(11)
        assertThat(a.totalBytesIngressLast24h).isEqualTo(100)
        assertThat(a.totalBytesEgressLast24h).isEqualTo(10)
        assertThat(a.latestTimeEnd).isEqualTo(now)
        assertThat(averages.map { it.appId }).containsExactly("a", "b")
        Unit  // keep this @Test method's return type Unit (runBlocking returns its block value)
    }

    @Test
    fun `removeAllScheduledForDeletionById deletes only flagged rows`() {
        val keep = makeFlow("a", timeEnd = 100, scheduledForDeletion = false)
        val drop = makeFlow("b", timeEnd = 100, scheduledForDeletion = true)
        dao.insertFlow(keep)
        dao.insertFlow(drop)

        dao.removeAllScheduledForDeletionById(listOf(keep.uuid, drop.uuid))

        assertThat(dao.getFlowById(keep.uuid)).isNotNull()
        assertThat(dao.getFlowById(drop.uuid)).isNull()
    }
}
