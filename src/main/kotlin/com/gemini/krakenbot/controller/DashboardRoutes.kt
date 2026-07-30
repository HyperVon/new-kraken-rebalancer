package com.gemini.krakenbot.controller

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.dashboardRouting() {
    val controller: DashboardController by inject()
    routing {
        controller.registerRoutes(this)
    }
}
