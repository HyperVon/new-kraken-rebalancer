package com.gemini.krakenbot.model

import com.gemini.krakenbot.codegen.GenerateStringConstants

@GenerateStringConstants(fileName = "KrakenAssetAliases", resource = "codegen/kraken-asset-aliases.yaml")
internal object KrakenAssetAliasesSchema

@GenerateStringConstants(fileName = "KrakenApiConstants", resource = "codegen/kraken-api-constants.yaml")
internal object KrakenApiConstantsSchema
