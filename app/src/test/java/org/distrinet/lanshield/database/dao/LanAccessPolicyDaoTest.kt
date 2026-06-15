package org.distrinet.lanshield.database.dao

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.testutil.getOrAwaitValue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LanAccessPolicyDaoTest {

    @get:Rule
    val instantTaskRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var dao: LanAccessPolicyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.LanAccessPolicyDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `getAllNoSystem excludes system apps`() {
        dao.insert(LanAccessPolicy("com.user.app", Policy.BLOCK, isSystem = false))
        dao.insert(LanAccessPolicy("com.system.app", Policy.BLOCK, isSystem = true))

        val nonSystem = dao.getAllNoSystem().getOrAwaitValue()
        assertThat(nonSystem.map { it.packageName }).containsExactly("com.user.app")
    }

    @Test
    fun `countByPolicy counts matching rows`() {
        dao.insert(LanAccessPolicy("a", Policy.BLOCK, isSystem = false))
        dao.insert(LanAccessPolicy("b", Policy.BLOCK, isSystem = false))
        dao.insert(LanAccessPolicy("c", Policy.ALLOW, isSystem = false))

        assertThat(dao.countByPolicy(Policy.BLOCK).getOrAwaitValue()).isEqualTo(2)
        assertThat(dao.countByPolicy(Policy.ALLOW).getOrAwaitValue()).isEqualTo(1)
    }

    @Test
    fun `getAllUnsyncedPolicy returns only unsynced rows of the given policy`() {
        dao.insert(LanAccessPolicy("blocked-dirty", Policy.BLOCK, isSystem = false, shouldSync = true))
        dao.insert(LanAccessPolicy("blocked-clean", Policy.BLOCK, isSystem = false, shouldSync = false))
        dao.insert(LanAccessPolicy("allowed-dirty", Policy.ALLOW, isSystem = false, shouldSync = true))

        assertThat(dao.getAllUnsyncedPolicy(Policy.BLOCK)).containsExactly("blocked-dirty")
    }

    @Test
    fun `setAllSynced clears the sync flag`() {
        dao.insert(LanAccessPolicy("a", Policy.BLOCK, isSystem = false, shouldSync = true))
        dao.insert(LanAccessPolicy("b", Policy.ALLOW, isSystem = false, shouldSync = true))

        dao.setAllSynced()

        assertThat(dao.getAllUnsynced()).isEmpty()
    }

    @Test
    fun `updatePolicyByPackageName changes the stored policy`() {
        dao.insert(LanAccessPolicy("a", Policy.BLOCK, isSystem = false))
        dao.updatePolicyByPackageName("a", Policy.ALLOW)

        assertThat(dao.getPolicyByPackageName("a").getOrAwaitValue()).isEqualTo(Policy.ALLOW)
    }
}
