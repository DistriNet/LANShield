package org.distrinet.lanshield.ui.lantraffic

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.distrinet.lanshield.database.dao.FlowDao
import javax.inject.Inject

@HiltViewModel
class LANTrafficViewModel @Inject constructor(flowDao: FlowDao) : ViewModel() {

    val liveFlowAverages = flowDao.getFlowAverages(0)
    val allFlows = flowDao.getAllFlows()
}