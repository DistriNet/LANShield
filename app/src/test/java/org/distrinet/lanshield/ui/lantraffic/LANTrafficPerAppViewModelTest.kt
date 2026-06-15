package org.distrinet.lanshield.ui.lantraffic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.junit.Before
import org.junit.Test

/** Unit tests for [LANTrafficPerAppViewModel] using MockK fakes for its DAOs. */
class LANTrafficPerAppViewModelTest {

    private val flowDao: FlowDao = mockk(relaxed = true)
    private val policyDao: LanAccessPolicyDao = mockk(relaxed = true)
    private val dataStore: DataStore<Preferences> = mockk(relaxed = true)

    private lateinit var viewModel: LANTrafficPerAppViewModel

    @Before
    fun setUp() {
        viewModel = LANTrafficPerAppViewModel(flowDao, policyDao, dataStore)
    }

    @Test
    fun `setting a non-default policy inserts the access policy`() = runTest {
        viewModel.onChangeAccessPolicy("com.example.app", Policy.BLOCK, isSystemApp = false)

        verify { policyDao.insert(LanAccessPolicy("com.example.app", Policy.BLOCK, false)) }
        verify(exactly = 0) { policyDao.delete(any()) }
    }

    @Test
    fun `setting the DEFAULT policy deletes the access policy`() = runTest {
        viewModel.onChangeAccessPolicy("com.example.app", Policy.DEFAULT, isSystemApp = true)

        verify { policyDao.delete(LanAccessPolicy("com.example.app", Policy.DEFAULT, true)) }
        verify(exactly = 0) { policyDao.insert(any()) }
    }

    @Test
    fun `getLANFlows delegates to the flow dao`() = runTest {
        val flows = emptyList<LANFlow>()
        every { flowDao.getFlowsByAppId("com.example.app") } returns flowOf(flows)

        assertThat(viewModel.getLANFlows("com.example.app").first()).isEqualTo(flows)
        verify { flowDao.getFlowsByAppId("com.example.app") }
    }

    @Test
    fun `clearLANFlows deletes the apps flows`() = runTest {
        viewModel.clearLANFlows("com.example.app")

        // clearLANFlows fires the delete on a detached IO scope; wait for it.
        verify(timeout = 2000) { flowDao.deleteFlowsWithAppId("com.example.app") }
    }
}
