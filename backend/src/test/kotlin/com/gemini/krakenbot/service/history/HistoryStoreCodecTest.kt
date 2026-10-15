package com.gemini.krakenbot.service.history

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class HistoryStoreCodecTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "encodes and decodes history strings" {
            decodeHistoryStrings("") shouldBe emptyList()

            val sample = listOf("foo", "bar,baz", "with\nnewline", "emoji🚀", "")
            val encoded = encodeHistoryStrings(sample)
            val decoded = decodeHistoryStrings(encoded)
            decoded shouldBe sample
        }

        "encodes and decodes history pairs" {
            decodeHistoryPairs("") shouldBe emptyMap()

            val sample = mapOf(
                "asset" to "XXBT",
                "class" to "currency",
                "nullable" to null,
                "unicode" to "valüé",
            )
            val encoded = encodeHistoryPairs(sample)
            val decoded = decodeHistoryPairs(encoded)
            decoded shouldBe sample
        }

        "historyPayloadHash produces deterministic SHA-256 for string values including nulls" {
            val hash1 = historyPayloadHash(listOf("a", null, "b"))
            val hash2 = historyPayloadHash(listOf("a", null, "b"))
            val hash3 = historyPayloadHash(listOf("a", "b", null))

            hash1.length shouldBe 64
            hash1 shouldBe hash2
            hash1 shouldNotBe hash3
        }

        "encodeHistoryAssetPartition formats asset and class map" {
            val encoded = encodeHistoryAssetPartition("currency", "XXBT")
            val decoded = decodeHistoryPairs(encoded)
            decoded shouldBe mapOf("class" to "currency", "asset" to "XXBT")
        }
    }
}
