package com.example.contract

import com.example.state.Sell
import com.example.state.Purchase
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

class SellingPaperContract : Contract {

    companion object {
        @JvmStatic
        val CONTRACT_REF = "com.example.contract.SellingPaperContract"
    }

    interface Commands : CommandData
    class Create : TypeOnlyCommandData(), Commands

    override fun verify(tx: LedgerTransaction) {
        // We only need the bid commands at this point to determine which part of the contract code to run.
        val bidCommand = tx.commands.requireSingleCommand<Commands>()
        val setOfSigners = bidCommand.signers.toSet()

        when (bidCommand.value) {
            is Create -> verifyCreate(tx, setOfSigners)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    private fun verifyCreate(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // Assert we have the right amount and type of states.
        "The can only one input state in an create purchase transaction." using (tx.inputStates.size == 1)
        "There must be two output states in an create purchase transaction." using (tx.outputStates.size == 2)

        val auctionInput = tx.inputsOfType<Sell>().single()
        val auctionOutput = tx.outputsOfType<Sell>().single()
        val bidOutput = tx.outputsOfType<Purchase>().single()

        // Assert stuff about the bid in relation to the auction state.
        "The purchase must be for this paper." using (bidOutput.paperReference == auctionOutput.linearId)
        "The paper must be updated by the amount purchase." using (bidOutput.amount == auctionOutput.highestBid)
        "The purchase must be higher or equal than start price" using (bidOutput.amount >= auctionOutput.startPrice)
        "The purchase must be higher or equal than highest sell price" using (bidOutput.amount >= auctionInput.highestBid)

        // Assert correct signer.
        "The paper sell must be signed by the manager and buyer." using (signers.containsAll(listOf(auctionInput.itemOwner.owningKey,bidOutput.buyer.owningKey)))
    }

}