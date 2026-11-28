package app.fieldwatch.sweep.spaces

import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SpaceReportsTest {
    private val log = inspection()

    @Test fun thermalMeasurementsAndPrivateUsbMetadataAreCoveredByTheirRespectiveDigests() {
        val t = SpaceThermalCheck(204, 263, 59, 3200, baselineDeltaDiffDeciC = 20,
            deviceLabel = "IR sensor", manufacturer = "USB vendor", serialNumber = "SN-12345")
        fun withThermal(reading: SpaceThermalCheck) = log.copy(thermalCoverageRecorded = true,
            checklist = listOf(log.checklist.first().copy(thermal = reading)) + log.checklist.drop(1))
        val measured = withThermal(t)
        val public = SpaceReports.report(measured)
        val full = SpaceReports.report(measured, true, true)
        val sealed = measured.copy(reportSha256 = public.sha256, archiveSha256 = full.sha256)
        assertTrue(SpaceReports.integrityValid(sealed))
        assertFalse(SpaceReports.integrityValid(sealed.copy(archiveSha256 = "")))
        val lines = SpacePdfLayout.pages(public) { text, _ -> text.length * 5f }.flatten().map { it.text }
        assertTrue(lines.any { "20.4 °C" in it && "26.3 °C" in it })
        assertFalse(lines.any { t.serialNumber in it || t.manufacturer in it || t.deviceLabel in it })
        for (changed in listOf(t.copy(deviceLabel = "altered"), t.copy(manufacturer = ""), t.copy(serialNumber = "changed"))) {
            val tampered = sealed.copy(checklist = withThermal(changed).checklist)
            assertEquals(public.sha256, SpaceReports.report(tampered).sha256)
            assertNotEquals(full.sha256, SpaceReports.report(tampered, true, true).sha256)
            assertFalse(SpaceReports.integrityValid(tampered))
        }
        for (changed in listOf(t.copy(ambientDeciC = 205), t.copy(peakDeciC = 265), t.copy(deltaDeciC = 60),
                t.copy(capturedAt = 3201), t.copy(baselineDeltaDiffDeciC = null))) {
            val tampered = sealed.copy(checklist = withThermal(changed).checklist)
            assertNotEquals(public.sha256, SpaceReports.report(tampered).sha256)
            assertNotEquals(full.sha256, SpaceReports.report(tampered, true, true).sha256)
            assertFalse(SpaceReports.integrityValid(tampered))
        }
        assertTrue(SpaceReports.integrityValid(log))
        assertFalse(SpaceReports.report(log).text().contains("USB thermal inspection"))
    }

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
        assertEquals("[space name withheld]", SpaceReports.redactSpaceName("Office LG", emptySequence(), sequenceOf("LG")))
        assertEquals("Airbnb Suite", SpaceReports.redactSpaceName("Airbnb Suite", emptySequence(), sequenceOf("IR", "1")))
        assertEquals("Unit 101", SpaceReports.redactSpaceName("Unit 101", emptySequence(), sequenceOf("1", "0")))
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
