package app.fieldwatch.sweep.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouterInventoryParserTest {
    private val capturedAt = 1_700_000_000_000L
    private val importedAt = capturedAt + 60_000

    @Test
    fun importPreservesOldReportedStateWithoutInventingAnObservation() {
        val result = parse("""{"schemaVersion":1,"capturedAt":$capturedAt,"networkLabel":"Owner network","devices":[{"hostname":"old-printer.local","addresses":["192.168.1.23","FE80::1%wlan0"],"macAddress":"aa-bb-cc-dd-ee-ff","reportedIdentifier":"lease-9","current":false,"lastSeenAt":${capturedAt - 86_400_000}}]}""")
        assertEquals(capturedAt, result.capturedAt)
        assertEquals("Owner network", result.networkLabel)
        val endpoint = result.endpoints.single()
        assertEquals(NetworkSource.ROUTER_IMPORT, endpoint.source)
        assertEquals(listOf("192.168.1.23", "fe80::1%wlan0"), endpoint.addresses)
        assertEquals("AA:BB:CC:DD:EE:FF", endpoint.reportedMacAddress)
        assertEquals("lease-9", endpoint.reportedIdentifier)
        assertEquals(false, endpoint.reportedCurrent)
        assertEquals(capturedAt - 86_400_000, endpoint.reportedLastSeenAt)
        assertEquals(importedAt, endpoint.importedAt)
        assertNull(endpoint.firstSeenAt)
        assertNull(endpoint.lastSeenAt)
        assertEquals("", endpoint.serviceType)
    }

    @Test
    fun lastSeenDoesNotBecomeCurrentAndIdsAreLocalToEachImport() {
        val document = inventory("""{"hostname":"shared-name.local","lastSeenAt":$capturedAt}""")
        val first = parse(document).endpoints.single()
        val second = parse(document).endpoints.single()
        assertNull(first.reportedCurrent)
        assertNotEquals(first.id, second.id)
        assertEquals(first.hostnames, second.hostnames)
    }

    @Test
    fun schemaTimeAndScalarTypesAreStrict() {
        listOf(
            """{"capturedAt":$capturedAt,"devices":[]}""",
            """{"schemaVersion":2,"capturedAt":$capturedAt,"devices":[]}""",
            """{"schemaVersion":"1","capturedAt":$capturedAt,"devices":[]}""",
            """{"schemaVersion":1,"capturedAt":"$capturedAt","devices":[]}""",
            """{"schemaVersion":1,"capturedAt":1.5,"devices":[]}""",
            """{"schemaVersion":1,"capturedAt":0,"devices":[]}""",
            """{"schemaVersion":1,"capturedAt":${importedAt + 1},"devices":[]}""",
            inventory("""{"hostname":"one","current":"true"}"""),
            inventory("""{"hostname":"one","lastSeenAt":${capturedAt + 1}}"""),
            inventory("""{"hostname":"one","lastSeenAt":0}"""),
            inventory("""{"hostname":123}"""),
            inventory("""{"addresses":null}"""),
            inventory("""{"addresses":[123]}"""),
            inventory("{}"),
        ).forEach { invalid { parse(it) } }
        assertTrue(parse(inventory("")).endpoints.isEmpty())
    }

    @Test
    fun unknownCredentialAndIdentityFieldsAreRejectedWithoutRepeatingSensitiveData() {
        listOf(
            """{"schemaVersion":1,"capturedAt":$capturedAt,"devices":[],"password":"private-secret"}""",
            inventory("""{"hostname":"one","password":"private-secret"}"""),
            inventory("""{"id":"private-secret","hostname":"one"}"""),
            inventory("""{"hostname":"one","addresses":["private-secret.example"]}"""),
            inventory("""{"hostname":"one","macAddress":"private-secret"}"""),
        ).forEach { document ->
            val message = invalid { parse(document) }.message.orEmpty()
            assertFalse(message.contains("private-secret"))
            assertFalse(message.contains(document))
            assertTrue(message.isNotBlank())
        }
    }

    @Test
    fun duplicateKeysAndDuplicateReportedRecordsCannotBeImported() {
        listOf(
            """{"schemaVersion":1,"capturedAt":$capturedAt,"capturedAt":${capturedAt - 1},"devices":[]}""",
            """{"schemaVersion":1,"capturedAt":$capturedAt,"captured\u0041t":$capturedAt,"devices":[]}""",
            inventory("""{"hostname":"one","hostname":"two"}"""),
            inventory("""{"hostname":"one"},{"hostname":"one"}"""),
            inventory("""{"macAddress":"AA:BB:CC:DD:EE:FF"},{"macAddress":"aa-bb-cc-dd-ee-ff"}"""),
            inventory("""{"reportedIdentifier":"lease-1"},{"reportedIdentifier":"lease-1"}"""),
            inventory("""{"addresses":["192.168.1.1"]},{"addresses":["192.168.1.1"]}"""),
            inventory("""{"addresses":["fd00::1"]},{"addresses":["FD00:0:0:0:0:0:0:1"]}"""),
            inventory("""{"addresses":["::ffff:192.0.2.1","0:0:0:0:0:ffff:c000:201"]}"""),
        ).forEach { invalid { parse(it) } }
    }

    @Test
    fun literalAddressValidationHandlesIpv6AndNeverAcceptsHostnamesOrAmbiguousIpv4() {
        listOf("192.168.1.1", "0.0.0.0", "255.255.255.255", "::", "::1", "2001:db8::abcd", "1:2:3:4:5:6:7:8", "::ffff:192.0.2.1", "fe80::1%wlan0", "fe80::1%4")
            .forEach { NetworkAddressValidator.validate(it) }
        listOf("example.local", "https://192.168.1.1", "127.1", "012.0.0.1", "256.0.0.1", "1.2.3.-1", "[::1]", "1:2:3", "::1::2", ":::1", "1:2:3:4:5:6:7:8::", "::ffff:999.0.0.1", "192.0.2.1::", "fe80::1%", "fe80::1%a/b", " ::1", "::1\n")
            .forEach { invalid { NetworkAddressValidator.validate(it) } }
    }

    @Test
    fun boundedRecordsTextNestingAndUtf8RejectAbusiveDocuments() {
        invalid { RouterInventoryParser.parse(ByteArray(NetworkLimits.MAX_IMPORT_BYTES + 1), importedAt) }
        invalid { RouterInventoryParser.parse(byteArrayOf(0xc3.toByte(), 0x28), importedAt) }
        invalid { parse(inventory((0..128).joinToString(",") { """{"reportedIdentifier":"r-$it"}""" })) }
        invalid { parse(inventory("""{"hostname":"${"x".repeat(256)}"}""")) }
        invalid { parse(inventory("""{"hostname":"one\u000aInjected"}""")) }
        invalid { parse("[".repeat(20) + "0" + "]".repeat(20)) }
        invalid { parse(inventory("""{"hostname":"one","addresses":[${(1..17).joinToString(",") { "\"192.168.1.$it\"" }}]}""")) }
    }

    @Test
    fun quotesBracesAndEscapedCommasInNamesDoNotConfuseDuplicateKeyGuard() {
        val document = inventory("""{"hostname":"A {name}, \"quoted\""}""")
        assertEquals("A {name}, \"quoted\"", parse(document).endpoints.single().hostnames.single())
    }

    private fun inventory(devices: String) = """{"schemaVersion":1,"capturedAt":$capturedAt,"devices":[$devices]}"""
    private fun parse(document: String) = RouterInventoryParser.parse(document.toByteArray(Charsets.UTF_8), importedAt)
    private fun invalid(block: () -> Unit): IllegalArgumentException {
        try { block(); fail("Expected invalid inventory to be rejected") } catch (error: IllegalArgumentException) { return error }
        throw AssertionError("Expected invalid inventory to be rejected")
    }
}
