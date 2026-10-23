package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata

/** Resolves configured symbols only from Kraken's explicit public asset-class metadata. */
internal object ConfiguredAssetTradingEligibility {
    fun requireEligible(configuredSymbols: List<String>, metadata: List<KrakenAssetMetadata>) {
        val metadataBySymbol = metadata.groupBy { Asset.normalizeKrakenAssetCode(it.assetId) }
        val unresolved = configuredSymbols
            .map(Asset::canonicalSymbol)
            .filterNot { Asset.normalizeKrakenAssetCode(it).equals(Asset.USD, ignoreCase = true) }
            .distinct()
            .mapNotNull { symbol ->
                val entries = metadataBySymbol[Asset.normalizeKrakenAssetCode(symbol)].orEmpty()
                when {
                    entries.isEmpty() -> "$symbol (no Kraken asset-class metadata)"

                    entries.any {
                        !it.assetClass.equals(KrakenApiConstants.ASSET_CLASS_CURRENCY, ignoreCase = true)
                    } ->
                        "$symbol (${entries.map { it.assetClass }.distinct().joinToString()})"

                    else -> null
                }
            }
        check(unresolved.isEmpty()) {
            "Configured assets lack a supported currency classification: ${unresolved.joinToString()}."
        }
    }
}
