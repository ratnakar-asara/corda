/*
 * Copyright 2015 Distributed Ledger Group LLC.  Distributed as Licensed Company IP to DLG Group Members
 * pursuant to the August 7, 2015 Advisory Services Agreement and subject to the Company IP License terms
 * set forth therein.
 *
 * All other rights reserved.
 */

package core.node

import co.paralleluniverse.fibers.Suspendable
import core.*
import core.crypto.SecureHash
import core.messaging.*
import core.protocols.ProtocolLogic
import core.serialization.serialize
import core.testutils.ALICE
import core.testutils.ALICE_KEY
import core.testutils.CASH
import core.utilities.BriefLogFormatter
import org.junit.Before
import org.junit.Test
import java.security.PublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimestamperNodeServiceTest : TestWithInMemoryNetwork() {
    lateinit var myNode: Pair<InMemoryNetwork.Handle, InMemoryNetwork.InMemoryNode>
    lateinit var serviceNode: Pair<InMemoryNetwork.Handle, InMemoryNetwork.InMemoryNode>
    lateinit var service: TimestamperNodeService

    val ptx = TransactionBuilder().apply {
        addInputState(StateRef(SecureHash.randomSHA256(), 0))
        addOutputState(100.DOLLARS.CASH)
    }

    val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    lateinit var mockServices: ServiceHub
    lateinit var serverKey: PublicKey

    init {
        BriefLogFormatter.initVerbose("dlg.timestamping.request")
    }

    @Before
    fun setup() {
        myNode = makeNode()
        serviceNode = makeNode()
        mockServices = MockServices(net = serviceNode.second, storage = MockStorageService())

        val timestampingNodeID = network.setupTimestampingNode(true).first
        (mockServices.networkMapService as MockNetworkMap).timestampingNodes.add(timestampingNodeID)
        serverKey = timestampingNodeID.identity.owningKey

        // And a separate one to be tested directly, to make the unit tests a bit faster.
        service = TimestamperNodeService(serviceNode.second, Party("Unit test suite", ALICE), ALICE_KEY)
    }

    class TestPSM(val server: LegallyIdentifiableNode, val now: Instant) : ProtocolLogic<Boolean>() {
        @Suspendable
        override fun call(): Boolean {
            val ptx = TransactionBuilder().apply {
                addInputState(StateRef(SecureHash.randomSHA256(), 0))
                addOutputState(100.DOLLARS.CASH)
            }
            ptx.addCommand(TimestampCommand(now - 20.seconds, now + 20.seconds), server.identity.owningKey)
            val wtx = ptx.toWireTransaction()
            // This line will invoke sendAndReceive to interact with the network.
            val sig = subProtocol(TimestampingProtocol(server, wtx.serialized))
            ptx.checkAndAddSignature(sig)
            return true
        }
    }

    @Test
    fun successWithNetwork() {
        val psm = runNetwork {
            val smm = StateMachineManager(MockServices(net = myNode.second), RunOnCallerThread)
            val logName = TimestamperNodeService.TIMESTAMPING_PROTOCOL_TOPIC
            val psm = TestPSM(mockServices.networkMapService.timestampingNodes[0], clock.instant())
            smm.add(logName, psm)
        }
        assertTrue(psm.isDone)
    }

    @Test
    fun wrongCommands() {
        // Zero commands is not OK.
        assertFailsWith(TimestampingError.RequiresExactlyOneCommand::class) {
            val wtx = ptx.toWireTransaction()
            service.processRequest(TimestampingMessages.Request(wtx.serialize(), myNode.first, "ignored"))
        }
        // More than one command is not OK.
        assertFailsWith(TimestampingError.RequiresExactlyOneCommand::class) {
            ptx.addCommand(TimestampCommand(clock.instant(), 30.seconds), ALICE)
            ptx.addCommand(TimestampCommand(clock.instant(), 40.seconds), ALICE)
            val wtx = ptx.toWireTransaction()
            service.processRequest(TimestampingMessages.Request(wtx.serialize(), myNode.first, "ignored"))
        }
    }

    @Test
    fun tooEarly() {
        assertFailsWith(TimestampingError.NotOnTimeException::class) {
            val now = clock.instant()
            ptx.addCommand(TimestampCommand(now - 60.seconds, now - 40.seconds), ALICE)
            val wtx = ptx.toWireTransaction()
            service.processRequest(TimestampingMessages.Request(wtx.serialize(), myNode.first, "ignored"))
        }
    }

    @Test
    fun tooLate() {
        assertFailsWith(TimestampingError.NotOnTimeException::class) {
            val now = clock.instant()
            ptx.addCommand(TimestampCommand(now - 60.seconds, now - 40.seconds), ALICE)
            val wtx = ptx.toWireTransaction()
            service.processRequest(TimestampingMessages.Request(wtx.serialize(), myNode.first, "ignored"))
        }
    }

    @Test
    fun success() {
        val now = clock.instant()
        ptx.addCommand(TimestampCommand(now - 20.seconds, now + 20.seconds), ALICE)
        val wtx = ptx.toWireTransaction()
        val sig = service.processRequest(TimestampingMessages.Request(wtx.serialize(), myNode.first, "ignored"))
        ptx.checkAndAddSignature(sig)
        ptx.toSignedTransaction(false).verifySignatures()
    }
}