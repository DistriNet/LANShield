package org.distrinet.lanshield.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.distrinet.lanshield.Policy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises [SettingsViewModel] against a real (file-backed) Preferences DataStore.
 * DataStore-core is pure JVM, so no Robolectric is needed; the view-model's
 * `viewModelScope` is driven by overriding the Main dispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        dataStoreScope = CoroutineScope(mainDispatcher + Job())
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(tmpFolder.root, "settings.preferences_pb")
        }
        viewModel = SettingsViewModel(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `allowDns defaults to false and reflects a toggle`() = runTest {
        viewModel.allowDns.test {
            assertThat(awaitItem()).isFalse()
            viewModel.onChangeAllowDns(true)
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `defaultPolicy defaults to DEFAULT and reflects a change`() = runTest {
        viewModel.defaultPolicy.test {
            assertThat(awaitItem()).isEqualTo(Policy.DEFAULT)
            viewModel.onChangeDefaultPolicy(Policy.BLOCK)
            assertThat(awaitItem()).isEqualTo(Policy.BLOCK)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `autostart toggle is persisted`() = runTest {
        viewModel.isAutoStartEnabled.test {
            assertThat(awaitItem()).isFalse()
            viewModel.onChangeAutoStart(true)
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
