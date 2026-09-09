package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.InceptionDisplayInfo
import com.gemini.krakenbot.service.InceptionDisplayStatus
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.time.Instant

class SettingsFormComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun config(inceptionDate: String? = null, comparisonStartDate: String? = null) = AppConfig(
        KrakenCredentials("test-key", "dGVzdC1wcml2YXRlLWtleQ=="),
        TestFixtures.settings(dryRun = true)
            .copy(inceptionDate = inceptionDate, comparisonStartDate = comparisonStartDate),
        listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
    )

    private fun render(settingsConfig: AppConfig, display: InceptionDisplayInfo, proposal: Instant? = null): String =
        createHTML().div {
            SettingsFormComponent().renderForm(
                this,
                settingsConfig,
                null,
                "csrf-token",
                inceptionDisplay = display,
                laterStartProposal = proposal,
            )
        }

    init {
        "approved pending renders the recovery progress marker" {
            val html = render(
                config(inceptionDate = "2026-01-01T00:00:00Z"),
                InceptionDisplayInfo(status = InceptionDisplayStatus.APPROVED_PENDING),
            )

            html shouldContain "inception-baseline-pending"
            html shouldContain "Approved start saved"
        }

        "approved ready renders the established baseline message" {
            val html = render(
                config(inceptionDate = "2026-01-01T00:00:00Z"),
                InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_READY,
                    message = "Comparison baseline established at 2026-01-01T00:00:00Z",
                ),
            )

            html shouldContain "Comparison baseline established at"
            html shouldNotContain "inception-baseline-pending"
        }

        "approved unavailable with a proposal renders the verified-start control" {
            val html = render(
                config(inceptionDate = "2026-01-01T00:00:00Z"),
                InceptionDisplayInfo(status = InceptionDisplayStatus.APPROVED_UNAVAILABLE),
                proposal = Instant.parse("2026-08-01T10:30:00Z"),
            )

            html shouldContain "Earliest verified comparison start"
            html shouldContain "Use verified start"
            html shouldContain "value='2026-08-01T10:30:00Z'"
        }

        "approved unavailable without a proposal renders no verified-start control" {
            val html = render(
                config(inceptionDate = "2026-01-01T00:00:00Z"),
                InceptionDisplayInfo(status = InceptionDisplayStatus.APPROVED_UNAVAILABLE),
            )

            html shouldContain "No trustworthy baseline could be established"
            html shouldNotContain "Use verified start"
        }

        "an accepted comparison start is echoed in the baseline block" {
            val html = render(
                config(inceptionDate = "2026-01-01T00:00:00Z", comparisonStartDate = "2026-06-07"),
                InceptionDisplayInfo(status = InceptionDisplayStatus.APPROVED_READY),
            )

            html shouldContain "Comparison Start: 2026-06-07"
        }

        "the full settings page renders through the body-context entry point" {
            val markup = createHTML().html {
                body {
                    SettingsFormComponent().render(
                        config(inceptionDate = "2026-01-01T00:00:00Z"),
                        null,
                        "csrf-token",
                    )
                }
            }

            markup shouldContain "csrf-token"
            markup shouldContain "name=\"comparisonStartDate\""
        }

        "full inference evidence renders its groups and empty date input" {
            val display = InceptionDisplayInfo(
                status = InceptionDisplayStatus.NOT_DETECTED,
                inferredStartText = "2026-01-02T00:00:00Z",
                inferredWindowStartText = "2026-01-01T00:00:00Z",
                inferredWindowEndText = "2026-01-03T00:00:00Z",
                firstPositiveText = "2026-01-02T00:00:01Z",
                inferredStartStrengthText = "HIGH",
                inferredStartReasonsText = "bot burst",
                inferredStartContradictionsText = "gap before start",
                strongestEpisodeText = "2026-01-05T00:00:00Z",
                strongestEpisodeStrengthText = "MEDIUM",
                strongestEpisodeReasonsText = "trading burst",
                earliestAmbiguousText = "2025-12-20T00:00:00Z",
                earlierAmbiguousCountText = "3",
                competingCandidatesText = "2 candidates",
                unsupportedMarketsText = "SOL",
                coverageText = "2025-12-01 to 2026-08-01",
            )
            val html = render(
                config(inceptionDate = "2026-01-02T00:00:00Z", comparisonStartDate = "2026-06-07T10:30:00Z"),
                display,
            )

            html shouldContain "2026-01-02T00:00:01Z"
            html shouldContain "bot burst"
            html shouldContain "gap before start"
            html shouldContain "trading burst"
            html shouldContain "3"
            html shouldContain "2025-12-20T00:00:00Z"
        }

        "renderForm defaults keep the settings form intact" {
            val markup = createHTML().div {
                SettingsFormComponent().renderForm(
                    this,
                    config(inceptionDate = "2026-01-01T00:00:00Z"),
                    null,
                    "csrf-token",
                )
            }

            markup shouldContain "name=\"comparisonStartDate\""
        }

        "no approved start renders no baseline status block" {
            val html = render(config(), InceptionDisplayInfo())

            html shouldNotContain "Approved start saved"
            html shouldNotContain "Comparison baseline established at"
        }
    }
}
