package com.example.contract

import com.example.state.Sell
import com.example.state.Purchase
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

class PaperContract : Contract {

    companion object {
        @JvmStatic
        val CONTRACT_REF = "com.example.contract.PaperContract"
    }

    interface Commands : CommandData
    class Start : TypeOnlyCommandData(), Commands
    class End : TypeOnlyCommandData(), Commands
    class AcceptBid : TypeOnlyCommandData(), Commands

    override fun verify(tx: LedgerTransaction) {
        val auctionCommand = tx.commands.requireSingleCommand<Commands>()
        val setOfSigners = auctionCommand.signers.toSet()

        when (auctionCommand.value) {
            is Start -> verifyStart(tx, setOfSigners)
            is End -> verifyEnd(tx, setOfSigners)
            is AcceptBid -> verifyPurchase(tx, setOfSigners)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    private fun verifyStart(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // Assert we have the right amount and type of states.
        "No inputs should be consumed when adding a paper." using (tx.inputStates.isEmpty())
        "Only one Sell state should be created when creating a paper." using (tx.outputStates.size == 1)

        // There can only be one output state and it must be a Sell state.
        val auction = tx.outputStates.single() as Sell

        // Assert stuff over the state.
        "A newly issued paper must have a positive start price." using (auction.startPrice > 0)
        "There must be a paper item name." using (auction.itemName != "")

        // Assert correct signers.
        "The paper must be signed by the manager only." using (signers.contains(auction.itemOwner.owningKey))
    }

    private fun verifyPurchase(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // Assert we have the right amount and type of states.
        "The can only one input state in an accept buy transaction." using (tx.inputStates.size == 1)
        "There must be two output states in an accept buy transaction." using (tx.outputStates.size == 2)

        val auctionInput = tx.inputsOfType<Sell>().single()
        val auctionOutput = tx.outputsOfType<Sell>().single()
        val bidOutput = tx.outputsOfType<Purchase>().single()

        // Assert stuff about the bid in relation to the auction state.
        "The purchase must be for this paper." using (bidOutput.paperReference == auctionOutput.linearId)
        "The paper must be updated by the amount buyer." using (bidOutput.amount == auctionOutput.highestBid)
        "The purchase of paper must be equal than start price" using (bidOutput.amount >= auctionOutput.startPrice)
        "The purchase of paper must be equal than highest offer" using (bidOutput.amount >= auctionInput.highestBid)

        // Assert correct signer.
        "The auction must be signed by the manager only." using (signers.contains(auctionInput.itemOwner.owningKey))
    }

    private fun verifyEnd(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // Assert we have the right amount and type of states.
        "Only one paper can be closed per transaction." using (tx.inputsOfType<Sell>().size == 1)
        "There must be no paper output states when the seller paper is ended." using (tx.outputsOfType<Purchase>().isEmpty())

        // Get references to auction state.
        val auction = tx.inputsOfType<Sell>().single()

        // Check the auction state is signed by the auction manager.
        "Ending paper transactions must be signed by the paper manager." using (signers.contains(auction.itemOwner.owningKey))
    }

}