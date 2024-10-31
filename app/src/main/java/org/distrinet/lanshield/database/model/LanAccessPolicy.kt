package org.distrinet.lanshield.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.distrinet.lanshield.Policy

@Entity(tableName = "lan_access_policies")
data class LanAccessPolicy(
    @PrimaryKey val packageName: String,
    var accessPolicy: Policy,
    var isSystem: Boolean,
    var shouldSync: Boolean = true,
)
