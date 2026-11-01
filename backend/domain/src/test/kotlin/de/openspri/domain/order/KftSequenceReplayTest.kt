package de.openspri.domain.order

import de.openspri.domain.message.CodeCheck
import de.openspri.domain.message.MessageCodeTableParser
import de.openspri.domain.message.MessageType
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays the expected message sequence of every test case of the KFT test case list through
 * [OrderLifecycle]: every message must be accepted in the given order, and the order must end in a final
 * state. This is the consistency check between our state machine and the official test cases (M2);
 * the full scenario tests over SOAP follow in M5.
 */
class KftSequenceReplayTest {

    @Test
    fun `test case list is complete`() {
        val cases = KftTestCaseList.all
        assertEquals(108, cases.count { it.sheet == "Testfälle Partner" })
        assertEquals(103, cases.count { it.sheet == "Testfälle Supplier" })
    }

    @TestFactory
    fun `every expected message sequence is accepted by the order lifecycle`(): List<DynamicTest> =
        KftTestCaseList.all.map { case ->
            DynamicTest.dynamicTest("${case.id} [${case.sheet.removePrefix("Testfälle ")}] ${case.description}") { replay(case) }
        }

    private fun replay(case: KftTestCase) {
        val parsed = KftSequenceParser.parse(case.sequence)
        assertTrue(parsed.unparsed.isEmpty(), "unparsed notation ${parsed.unparsed} in '${case.sequence}'")
        assertTrue(parsed.events.isNotEmpty(), "no events in '${case.sequence}'")
        val first = parsed.events.first()
        var state = when {
            first is OrderEvent.Message && first.type == MessageType.KDLE -> OrderLifecycle.announcedByLeistungserbringer()
            first is OrderEvent.Message && first.type == MessageType.AKM_PV -> OrderLifecycle.handoverAnnounced()
            else -> OrderLifecycle.submitted(case.businessCase, connectivity = case.connectivity)
        }
        parsed.events.forEachIndexed { index, event ->
            state = when (val transition = OrderLifecycle.apply(state, event)) {
                is Transition.Accepted -> transition.state
                is Transition.Rejected -> fail("step ${index + 1} $event rejected: ${transition.reason}\n  sequence: ${case.sequence}")
            }
        }
        assertTrue(state.finished, "order not finished after '${case.sequence}': $state")
    }

    /**
     * Every explicit message code in the test case list must exist in the message code table and be defined
     * for the message type and business case. Runs where the AK table is available locally (not part of the repository).
     */
    @Test
    fun `message codes of the test case list exist in the message code table`() {
        val file = Path.of(System.getProperty("user.dir"), "../../docs/spec-original/SPRI_Meldungscodetabelle.csv").normalize()
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(file), "AK message code table not available")
        val catalog = MessageCodeTableParser.parse(Files.readAllBytes(file), file.fileName.toString())
        val problems = sortedSetOf<String>()
        KftTestCaseList.all.forEach { case ->
            KftSequenceParser.parse(case.sequence).events.filterIsInstance<OrderEvent.Message>().forEach { message ->
                message.codes.forEach { code ->
                    val check = catalog.check(code, message.type, case.businessCase)
                    if (check is CodeCheck.Rejected) problems += "${case.id}: ${check.reason}"
                }
            }
        }
        // Deviations between the test case list and the table are resolved by documented local
        // additions (LocalMessageCodes, docs/spec-abweichungen.md SA-001/SA-008); new ones must fail.
        assertTrue(problems.isEmpty(), "message codes not covered by the table:\n${problems.joinToString("\n")}")
    }
}
