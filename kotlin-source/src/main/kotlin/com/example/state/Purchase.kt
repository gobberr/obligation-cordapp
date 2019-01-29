package com.example.state

import com.example.schema.BidSchemaV1
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

data class Purchase(
        val amount: Int,
        val buyer: Party,
        val paperOwner: Party,
        val paperReference: UniqueIdentifier,
        override val linearId: UniqueIdentifier = UniqueIdentifier()
) : LinearState, QueryableState {
    override val participants: List<AbstractParty> = listOf(buyer,paperOwner)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is BidSchemaV1 -> BidSchemaV1.PersistentBid(
                    this.paperOwner.name.toString(),
                    this.buyer.name.toString(),
                    this.amount,
                    this.paperReference.id.toString(),
                    this.linearId.id.toString()
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }
    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(BidSchemaV1)

}