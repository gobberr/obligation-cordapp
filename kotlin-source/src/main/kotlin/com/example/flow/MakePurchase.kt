package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.PaperContract
import com.example.state.Sell
import com.example.state.Purchase
import com.example.contract.SellingPaperContract
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.seconds
import net.corda.core.utilities.unwrap
import java.time.Instant
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step


/**
 * This pair of flows handles the making a Purchase to a particular Sell.
 */
object MakePurchase {

    /**
     * Takes an amount of currency and a auction reference then creates a new bid state and updates the existing
     * auction state to reflect the new bid.
     */
    @StartableByRPC
    @InitiatingFlow
    class Initiator(
            private val amount: Int,
            private val paperReference: String
    ) : FlowLogic<SignedTransaction>() {



        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Creating a new Sell.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()


        @Suspendable
        override fun call(): SignedTransaction {
            // Pick a notary. Don't care which one.
            val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION

            // Get the auction state corresponding to the provided ID from our vault.
            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(UniqueIdentifier.fromString(paperReference)))
            val auctionInputStateAndRef = serviceHub.vaultService.queryBy<Sell>(queryCriteria).states.single()
            val auctionState = auctionInputStateAndRef.state.data

            // Assemble the other transaction components.
            // Commands:
            val acceptAuctionCommand = Command(PaperContract.AcceptBid(), auctionState.itemOwner.owningKey)
            val createAuctionCommand = Command(SellingPaperContract.Create(), listOf(ourIdentity.owningKey, auctionState.itemOwner.owningKey))

            // Output states:
            val bidOutputState = Purchase(amount, serviceHub.myInfo.legalIdentities.first(), auctionState.itemOwner, UniqueIdentifier.fromString(paperReference))
            val bitOutputStateAndContract = StateAndContract(bidOutputState, SellingPaperContract.CONTRACT_REF)
            val auctionOutputState = auctionState.copy(highestBid=amount,auctionWinner=serviceHub.myInfo.legalIdentities.first())
            val auctionOutputStateAndContract = StateAndContract(auctionOutputState, PaperContract.CONTRACT_REF)

            // Build the transaction.
            val utx = TransactionBuilder(notary = notary).withItems(
                    bitOutputStateAndContract, // Output
                    auctionOutputStateAndContract, // Output
                    auctionInputStateAndRef, // Input
                    acceptAuctionCommand, // Command
                    createAuctionCommand  // Command
            )

            // Set the time for when this transaction happened.
            utx.setTimeWindow(Instant.now(), 30.seconds)

            // Sign, sync identiStartties, finalise and record the transaction.
            val ptx = serviceHub.signInitialTransaction(builder = utx, signingPubKeys = listOf(ourIdentity.owningKey))
            val session = initiateFlow(auctionState.itemOwner)
            subFlow(IdentitySyncFlow.Send(otherSide = session, tx = ptx.tx))
            val stx = subFlow(CollectSignaturesFlow(ptx, setOf(session), listOf(ourIdentity.owningKey)))
            val ftx = subFlow(FinalityFlow(stx))

            // Send list of auction paricipants to broadcast transaction
            session.sendAndReceive<Unit>(auctionState.AuctionParticipants)

            return ftx
        }

    }

    /**
     * This side is only run by the auction creator who checks the bid then waits for the bid
     * transaction to be committed and broadcasts it to all the parties on the business network.
     */
    @InitiatedBy(Initiator::class)
    class Responder(val otherSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            subFlow(IdentitySyncFlow.Receive(otherSideSession = otherSession))

            // As the manager, we might want to do some checking of the bid before we sign it.
            val flow = object : SignTransactionFlow(otherSession) {
                override fun checkTransaction(stx: SignedTransaction) = Unit
            }

            val stx = subFlow(flow)

            // Once the transaction has been committed then we then broadcast from the manager.
            val AuctionParticipants = otherSession.receive<List<Party>>().unwrap { it }
            val ftx = waitForLedgerCommit(stx.id)
            subFlow(BroadcastTransaction(ftx, AuctionParticipants))

            // We want the other side to block or at least wait a while for the transaction to be broadcast.
            otherSession.send(Unit)
        }

    }

}