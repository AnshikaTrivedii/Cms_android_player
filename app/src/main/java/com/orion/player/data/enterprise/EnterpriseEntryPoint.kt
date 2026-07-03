package com.orion.player.data.enterprise

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EnterpriseEntryPoint {
    fun deviceLogCollector(): DeviceLogCollector
}
