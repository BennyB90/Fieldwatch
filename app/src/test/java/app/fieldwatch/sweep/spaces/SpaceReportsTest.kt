package app.fieldwatch.sweep.spaces

import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SpaceReportsTest {
    private val log = inspection()

    @Test fun defaultRedactsMetadataNotesAddressesNamesAndCoordinatesAcrossFormats() {
        val contaminated = log.copy(spaceName = "$ADDRESS $LAT", redactedSpaceName = "$ADDRESS $LAT",
            checklist = log.checklist.map { it.copy(title = "injected SSID", guidance = "private guidance") })
        val report = SpaceReports.report(contaminated)
        listOf(report.json(), report.text()).forEach { text ->
            listOf(ADDRESS, SSID, SECRET_NOTE, LAT.toString(), LON.toString(), "private wifi status", "secret custom signature",
                "private ble status", "secret-phone", "injected SSID", "private guidance").forEach { assertFalse(it, text.contains(it)) }
            assertTrue(text.contains(SpaceReports.DISCLAIMER))
            assertTrue(text.contains("Human-reviewed: 7 of 8"))
            assertTrue(text.contains("New since baseline: 1"))
        }
        assertTrue(SpaceReports.report(log).text().contains("Space: Unit 4B"))
    }

    @Test fun coordinatesAndIdentifiersRequireIndependentOptIns() {
        for (ids in listOf(false, true)) for (coords in listOf(false, true)) {
            val report = SpaceReports.report(log, ids, coords)
            listOf(report.json(), report.text()).forEach { text ->
                listOf(ADDRESS, SSID, SECRET_NOTE).forEach { assertEquals(it, ids, text.contains(it)) }
                listOf(LAT.toString(), LON.toString()).forEach { assertEquals(it, coords, text.contains(it)) }
            }
        }
    }

    @Test fun standaloneDecimalIntegerPairsAndRadioNamesInSpaceLabelsAreWithheld() {
        val name = "Unit N43.567W78.600 near loc43,-78end"
        val input = log.copy(spaceName = name, redactedSpaceName = name)
        listOf(false, true).forEach {
            val text = SpaceReports.report(input, includeIdentifiers = it).text()
            assertFalse(text.contains("43.567"))
            assertFalse(text.contains("78.600"))
            assertFalse(text.contains("43,-78"))
        }
        assertFalse(SpaceReports.redactSpaceName("Room $SSID", sequenceOf(SSID.lowercase())).contains(SSID))
        assertEquals("[space name withheld]", SpaceReports.redactSpaceName("Room GuestWifi", sequenceOf("Guest\u200BWifi ", "GuestWifi ")))
        assertEquals("Unit 4B", SpaceReports.redactSpaceName("Unit 4B", sequenceOf("4", "B", "Unmatched radio")))
    }

    @Test fun externalReaderCanHashTheExactUtf8PayloadAndEveryPresentationUsesIt() {
        val report = SpaceReports.report(log)
        val payload = Json.parseToJsonElement(report.json()).jsonObject.getValue("payload").toString()
        val external = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(external, report.sha256)
        assertTrue(report.text().endsWith(report.sha256))
        assertEquals(64, report.sha256.length)
        assertNotEquals(report.sha256, SpaceReports.report(log, true).sha256)
        assertNotEquals(report.sha256, SpaceReports.report(log.copy(completedAt = 4500)).sha256)
        assertEquals(report.sha256, SpaceReports.report(log.copy(reportSha256 = "untrusted")).sha256)
        assertEquals(report.sha256, SpaceReports.report(log.copy(checklist = log.checklist.map { it.copy(note = "other private note") })).sha256)
    }

    @Test fun canonicalPayloadSurvivesLocaleAndTimezoneChanges() {
        val before = SpaceReports.report(log)
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))
            assertEquals(before, SpaceReports.report(log))
        } finally { Locale.setDefault(locale); TimeZone.setDefault(zone) }
    }
}
