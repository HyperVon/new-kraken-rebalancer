package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.service.impl.history.InceptionRecoveryService
import com.gemini.krakenbot.service.impl.history.LedgersSyncService
import com.gemini.krakenbot.service.impl.history.TradeHistoryQueryService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import com.gemini.krakenbot.service.impl.history.TradeHistorySnapshotStore
import com.gemini.krakenbot.service.impl.history.TradeHistorySyncService
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Instant

class InceptionDisplayInfoTest : TradeHistoryServiceTestBase() {
    init {
        "getDetectedInceptionDisplayInfo_autoSource_returnsUtcDateText" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val service = createService()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns "auto"

                val info = service.getDetectedInceptionDisplayInfo()

                info.dateText shouldBe "2024-03-15"
                info.source shouldBe "auto"
                info.inProgress shouldBe false
            }
        }

        "getDetectedInceptionDisplayInfo_configuredSource_treatedAsAbsent" {
            runTest {
                val epochMs = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
                val service = createService()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns epochMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns "configured"

                val info = service.getDetectedInceptionDisplayInfo()

                info.dateText.shouldBeNull()
                info.source.shouldBeNull()
                info.inProgress shouldBe false
            }
        }

        "getDetectedInceptionDisplayInfo_futureEpoch_treatedAsAbsent" {
            runTest {
                val futureMs = System.currentTimeMillis() + 86_400_000L
                val service = createService()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns futureMs.toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns "auto"

                val info = service.getDetectedInceptionDisplayInfo()

                info.dateText.shouldBeNull()
                info.inProgress shouldBe false
            }
        }

        "getDetectedInceptionDisplayInfo_missingMetadata_returnsEmpty" {
            runTest {
                val service = createService()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
                } returns null

                val info = service.getDetectedInceptionDisplayInfo()

                info.dateText.shouldBeNull()
                info.source.shouldBeNull()
                info.inProgress shouldBe false
            }
        }

        "getDetectedInceptionDisplayInfo_inProgressNoCache_returnsInProgress" {
            runTest {
                val sync = mockk<TradeHistorySyncService>()
                coEvery { sync.getSyncMetadata(any()) } returns null
                val recovery = mockk<InceptionRecoveryService>()
                coEvery { recovery.getStatus() } returns
                    InceptionRecoveryStatus(status = InceptionRecoveryStatus.IN_PROGRESS)
                val service = TradeHistoryServiceImpl(
                    mockk<TradeHistorySnapshotStore>(relaxed = true),
                    mockk<TradeHistoryQueryService>(relaxed = true),
                    sync,
                    mockk<LedgersSyncService>(relaxed = true),
                    recovery,
                )

                val info = service.getDetectedInceptionDisplayInfo()

                info.dateText.shouldBeNull()
                info.inProgress shouldBe true
            }
        }
    }
}
