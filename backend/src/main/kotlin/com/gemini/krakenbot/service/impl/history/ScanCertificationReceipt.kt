package com.gemini.krakenbot.service.impl.history

/**
 * Collects page-level completeness evidence while a paginated historical scan runs.
 *
 * A scan proves authoritative completeness only when every page carried an authoritative count and
 * pagination never shifted or restarted. Count-less pages and any detected shift leave
 * [authoritativeCompletenessProven] false. Callers must read the receipt only after the scan flow
 * completed without throwing; a failed pull must never look like a proof.
 */
internal class ScanCertificationReceipt {
    private var everyPageAuthoritative = true

    fun recordPage(authoritativeTotal: Boolean, paginationShifted: Boolean) {
        if (!authoritativeTotal || paginationShifted) {
            everyPageAuthoritative = false
        }
    }

    val authoritativeCompletenessProven: Boolean
        get() = everyPageAuthoritative
}

/** Outcome of [decideCertifiedCoverageAdvance] for logging and persistence decisions. */
internal data class CertifiedCoverageAdvance(val advances: Boolean, val tailIsContiguous: Boolean)

/**
 * Decides whether a completed scan may extend the durable certified coverage horizon.
 *
 * Certified coverage advances only when the scan carried authoritative completeness proof, the
 * proven tail is contiguous with the previously certified horizon (an incremental scan may overlap
 * it, but a proof starting after it must never bridge an unproven gap), and the new horizon does not
 * move backwards. The ordinary sync watermark is a separate consent: it follows successful requests
 * regardless of this decision.
 */
internal fun decideCertifiedCoverageAdvance(
    storedHorizonSec: Long?,
    certifiedFromSec: Long?,
    successfulHorizonSec: Long,
    authoritativeCompletenessProven: Boolean,
    extendsCertifiedTail: Boolean,
): CertifiedCoverageAdvance {
    val tailIsContiguous = !extendsCertifiedTail || (
        certifiedFromSec != null &&
            (storedHorizonSec == null || certifiedFromSec <= storedHorizonSec)
        )
    return CertifiedCoverageAdvance(
        advances = authoritativeCompletenessProven && tailIsContiguous &&
            (storedHorizonSec == null || successfulHorizonSec >= storedHorizonSec),
        tailIsContiguous = tailIsContiguous,
    )
}
