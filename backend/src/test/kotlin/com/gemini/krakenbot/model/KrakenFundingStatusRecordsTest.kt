package com.gemini.krakenbot.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class KrakenFundingStatusRecordsTest :
    StringSpec({
        "funding status records retain defaults, optional metadata, and value semantics" {
            val at = Instant.parse("2026-10-10T12:00:00Z")
            val deposit = DepositStatusRecord(
                refid = "deposit-1",
                asset = "XXBT",
                amount = BigDecimal("0.25"),
                time = at,
                status = "Success",
            )
            deposit.fee shouldBe BigDecimal.ZERO
            deposit.hasAuthoritativeFee shouldBe false
            val enrichedDeposit = deposit.copy(
                txid = "TX-1",
                fee = BigDecimal("0.0001"),
                method = "Lightning",
                hasAuthoritativeFee = true,
                methodId = "method-1",
            )
            val (
                depositRefid,
                depositTxid,
                depositAsset,
                depositAmount,
                depositFee,
                depositTime,
                depositStatus,
                depositMethod,
                depositFeeAuthority,
                depositMethodId,
            ) = enrichedDeposit
            listOf(
                depositRefid,
                depositTxid,
                depositAsset,
                depositAmount,
                depositFee,
                depositTime,
                depositStatus,
                depositMethod,
                depositFeeAuthority,
                depositMethodId,
            ) shouldBe listOf(
                "deposit-1",
                "TX-1",
                "XXBT",
                BigDecimal("0.25"),
                BigDecimal("0.0001"),
                at,
                "Success",
                "Lightning",
                true,
                "method-1",
            )
            enrichedDeposit shouldBe enrichedDeposit.copy()
            enrichedDeposit.hashCode() shouldBe enrichedDeposit.copy().hashCode()
            enrichedDeposit.toString().contains("method-1") shouldBe true

            val withdrawal = WithdrawStatusRecord(
                refid = "withdraw-1",
                asset = "ZUSD",
                amount = BigDecimal("20.00"),
                fee = BigDecimal("1.00"),
                time = at.plusSeconds(1),
                status = "Pending",
                hasAuthoritativeFee = true,
            )
            withdrawal.hasAuthoritativeFee shouldBe true
            val enrichedWithdrawal = withdrawal.copy(txid = "TX-2", method = "ACH", methodId = "method-2")
            val (
                withdrawalRefid,
                withdrawalTxid,
                withdrawalAsset,
                withdrawalAmount,
                withdrawalFee,
                withdrawalTime,
                withdrawalStatus,
                withdrawalMethod,
                withdrawalFeeAuthority,
                withdrawalMethodId,
            ) = enrichedWithdrawal
            listOf(
                withdrawalRefid,
                withdrawalTxid,
                withdrawalAsset,
                withdrawalAmount,
                withdrawalFee,
                withdrawalTime,
                withdrawalStatus,
                withdrawalMethod,
                withdrawalFeeAuthority,
                withdrawalMethodId,
            ) shouldBe listOf(
                "withdraw-1",
                "TX-2",
                "ZUSD",
                BigDecimal("20.00"),
                BigDecimal("1.00"),
                at.plusSeconds(1),
                "Pending",
                "ACH",
                true,
                "method-2",
            )
            enrichedWithdrawal shouldBe enrichedWithdrawal.copy()
            enrichedWithdrawal.hashCode() shouldBe enrichedWithdrawal.copy().hashCode()
            enrichedWithdrawal.toString().contains("method-2") shouldBe true

            val transfer = InternalTransferRecord(
                refid = "transfer-1",
                asset = "XETH",
                amount = BigDecimal("1.5"),
                time = at.plusSeconds(2),
            )
            val routedTransfer = transfer.copy(
                sourceWallet = "spot",
                destinationWallet = "earn",
                ledgerType = "transfer",
            )
            val (
                transferRefid,
                transferAsset,
                transferAmount,
                transferTime,
                sourceWallet,
                destinationWallet,
                ledgerType,
            ) = routedTransfer
            listOf(
                transferRefid,
                transferAsset,
                transferAmount,
                transferTime,
                sourceWallet,
                destinationWallet,
                ledgerType,
            ) shouldBe listOf(
                "transfer-1",
                "XETH",
                BigDecimal("1.5"),
                at.plusSeconds(2),
                "spot",
                "earn",
                "transfer",
            )
            routedTransfer shouldBe routedTransfer.copy()
            routedTransfer.hashCode() shouldBe
                routedTransfer.copy().hashCode()
            routedTransfer.toString().contains("destinationWallet=earn") shouldBe
                true
        }
    })
