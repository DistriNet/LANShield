package org.distrinet.lanshield.database.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.model.OpenPorts
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TreeSet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenPortsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: OpenPortsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.OpenPortsDao()
    }

    @After
    fun tearDown() = db.close()

    private fun openPorts(
        packageName: String,
        tcp: Collection<Int> = emptyList(),
        shouldSync: Boolean = true,
    ): OpenPorts = OpenPorts.createInstance(packageName, packageName).copy(
        tcpPorts = TreeSet(tcp),
        shouldSync = shouldSync,
    )

    @Test
    fun `getAllShouldSync returns only rows flagged for sync`() {
        dao.insertAll(listOf(openPorts("a", shouldSync = true), openPorts("b", shouldSync = false)))

        assertThat(dao.getAllShouldSync().map { it.packageName }).containsExactly("a")
    }

    @Test
    fun `tcp ports survive the sorted-set converter round-trip`() {
        dao.insert(openPorts("a", tcp = listOf(443, 22, 80)))

        val stored = dao.getAllShouldSync().single()
        assertThat(stored.tcpPorts).containsExactly(22, 80, 443).inOrder()
    }

    @Test
    fun `varargs insert stores every row`() {
        dao.insertAll(openPorts("a"), openPorts("b"), openPorts("c"))

        assertThat(dao.getAllShouldSync()).hasSize(3)
    }
}
