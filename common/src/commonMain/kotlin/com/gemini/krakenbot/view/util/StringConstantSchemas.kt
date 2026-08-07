package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.codegen.GenerateStringConstants

@GenerateStringConstants(fileName = "CssThemeVars", resource = "codegen/css-theme.yaml")
internal object CssThemeVarsSchema

@GenerateStringConstants(fileName = "HtmlAttrs", resource = "codegen/html-attrs.yaml")
internal object HtmlAttrsSchema

@GenerateStringConstants(fileName = "RouteConstants", resource = "codegen/route-constants.yaml")
internal object RouteConstantsSchema

@GenerateStringConstants(fileName = "ViewText", resource = "codegen/view-text.yaml")
internal object ViewTextSchema
