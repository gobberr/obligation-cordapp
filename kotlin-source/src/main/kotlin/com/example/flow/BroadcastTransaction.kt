package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.sun.org.apache.xalan.internal.lib.NodeInfo
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowException
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.SendTransactionFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.messaging.CordaRPCOps

/**
 * Filters out any notary identities and removes our identity, then broadcasts the [SignedTransaction] to all the
 * remaining identities.
 */
@InitiatingFlow
class BroadcastTransaction(val stx: SignedTransaction,val nodes: List<Party> /*, private val rpcOps: CordaRPCOps*/) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {

        //FIXME: filter node networkmap
        //private val myLegalName: CordaX500Name = rpcOps.nodeInfo().legalIdentities.first().name

        // Get a list of all identities from the network map cache.
        var everyone = serviceHub.networkMapCache.allNodes.flatMap { it.legalIdentities }

        // If nodes list is not empty, only broadcast to this nodes
        if (!nodes.isEmpty()){
            for (party in everyone){
                if (!(nodes.contains(party))) {
                    everyone = everyone.filter { it.equals(party).not() }

                }
            }
        }

        // Get networkmap node in order to filter it
        val networkMapName = CordaX500Name(
                organisation = "Networkmap",
                locality = "London",
                country = "GB")

        val networkMap: Party = serviceHub.identityService.wellKnownPartyFromX500Name(networkMapName) ?: throw IllegalArgumentException("Couldn't find node Networkmap")

        // Filter out the notary identities and remove our identity.
        val everyoneButMeAndNotary = everyone.filter { serviceHub.networkMapCache.isNotary(it).not() } - ourIdentity - networkMap

        // Create a session for each remaining party.
        val sessions = everyoneButMeAndNotary.map { initiateFlow(it) }

        // Send the transaction to all the remaining parties.
        sessions.forEach {
            subFlow(SendTransactionFlow(it, stx))
        }
    }

}