package com.example.plugin

import com.example.api.AuctionApi
import net.corda.core.messaging.CordaRPCOps
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function

class PaperWebPlugin : WebServerPluginRegistry {
   /**
    * A list of classes that expose web APIs.
    */
   override val webApis = listOf(Function(::AuctionApi))

   /**
    * A list of directories in the resources directory that will be served by Jetty under /web.
    */
   override val staticServeDirs = mapOf(
           // This will serve the exampleWeb directory in resources to /web/example
           "example" to javaClass.classLoader.getResource("exampleWeb").toExternalForm()
   )
}
