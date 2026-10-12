package app.fieldwatch.sweep

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepReportsTest {
    private val mac = "DE:AD:BE:EF:12:34"
    private val ssid = "Secret-bedroom-SSID"
    private val uas = "PRIVATE-UAS-SERIAL-993"
    private val latitude = 34.1234567
    private val longitude = -118.7654321

    @Test
    fun defaultReportsDoNotLeakIdentifiersOrCoordinatesThroughAnyFreeformField() {
        val session = privateSession()
        listOf(SweepReports.text(session), SweepReports.json(session)).forEach { report ->
            listOf(mac, ssid, uas, latitude.toString(), longitude.toString(), "secret-session-token", "secret-next-step", "secret-status", "secret-error", "secret-interpretation", "secret-phone").forEach {
                assertFalse("Leaked $it", report.contains(it))
            }
            assertTrue(report.contains("Tile Trackers"))
            assertTrue(report.contains("cannot certify a safe room"))
            assertTrue(report.contains("unsupported"))
        }
        val json = Json.parseToJsonElement(SweepReports.json(session)).jsonObject
        assertEquals("session", json["id"]!!.jsonPrimitive.content)
        val finding = json["findings"]!!.jsonArray.single().jsonObject
        assertEquals("finding-1", finding["id"]!!.jsonPrimitive.content)
        assertTrue(finding["dronePins"]!!.jsonArray.isEmpty())
    }

    @Test
    fun coordinateOptInDoesNotRevealIdentifiers() {
        listOf(SweepReports.text(privateSession(), includeCoordinates = true), SweepReports.json(privateSession(), includeCoordinates = true)).forEach { report ->
            assertTrue(report.contains(latitude.toString()))
            assertTrue(report.contains(longitude.toString()))
            listOf(mac, uas, ssid, "secret-session-token").forEach { assertFalse(report.contains(it)) }
        }
    }

    @Test
    fun identifierOptInDoesNotRevealCoordinatesEvenInAdvertisedNames() {
        listOf(SweepReports.text(privateSession(), includeIdentifiers = true), SweepReports.json(privateSession(), includeIdentifiers = true)).forEach { report ->
            assertTrue(report.contains(mac))
            assertTrue(report.contains(uas))
            assertTrue(report.contains(ssid))
            assertFalse(report.contains(latitude.toString()))
            assertFalse(report.contains(longitude.toString()))
        }
    }

    @Test
    fun bothExplicitOptInsRevealChosenIdsAndValidPinsAndUnknownAge() {
        val report = SweepReports.text(privateSession(), includeIdentifiers = true, includeCoordinates = true)
        listOf(mac, ssid, uas, latitude.toString(), longitude.toString()).forEach { assertTrue(report.contains(it)) }
        assertTrue(report.contains("coordinate observation time unknown"))
        assertFalse(report.contains("NaN"))
    }

    @Test
    fun defaultReportPreservesCoverageAndClassCountsWithoutUnsafeStatusText() {
        val json = Json.parseToJsonElement(SweepReports.json(privateSession())).jsonObject
        val coverage = json["coverage"]!!.jsonObject
        assertEquals("22", coverage["bleSamples"]!!.jsonPrimitive.content)
        assertEquals("false", coverage["wifiReady"]!!.jsonPrimitive.content)
        assertEquals("true", coverage["bleExtendedSupported"]!!.jsonPrimitive.content)
        assertTrue(SweepReports.text(privateSession()).contains("Drone indicator: 1"))
    }

    @Test
    fun recoveredInterruptedSessionExportsUnknownEndAndDurationWithoutInventingAnEnd() {
        val recovered = privateSession().copy(state = SweepSessionState.INTERRUPTED, endedAt = null)
        val json = Json.parseToJsonElement(SweepReports.json(recovered)).jsonObject
        assertEquals(JsonNull, json["endedAt"])
        assertEquals(JsonNull, json["durationMillis"])
        assertEquals("INTERRUPTED", json["state"]!!.jsonPrimitive.content)
        val text = SweepReports.text(recovered)
        assertTrue(text.contains("Ended: not recorded"))
        assertTrue(text.contains("Duration: unavailable"))
        assertFalse(text.contains("Duration: 0"))
    }

    @Test
    fun reportDurationUsesOnlyAValidRecordedEnd() {
        val complete = privateSession()
        val json = Json.parseToJsonElement(SweepReports.json(complete)).jsonObject
        assertEquals("60000", json["durationMillis"]!!.jsonPrimitive.content)
        assertTrue(SweepReports.text(complete).contains("Duration: 60.0 seconds"))
        val inconsistent = complete.copy(endedAt = complete.startedAt - 1L)
        assertEquals(JsonNull, Json.parseToJsonElement(SweepReports.json(inconsistent)).jsonObject["durationMillis"])
        assertTrue(SweepReports.text(inconsistent).contains("Duration: unavailable"))
    }

    @Test
    fun demoIsClearlySyntheticInSessionAndBothExports() {
        val session = SweepDemo.session(1_800_000_000_000L)
        assertTrue(session.demo)
        assertEquals(SweepSessionState.COMPLETE, session.state)
        assertEquals(SweepCategory.entries.toSet(), session.findings.map { it.category }.toSet())
        assertTrue(session.findings.all { it.name.contains("SAMPLE") && it.evidence.any { evidence -> evidence.label == "Synthetic sample" } })
        assertTrue(SweepReports.text(session).contains("SYNTHETIC SAMPLE REPORT"))
        assertTrue(SweepReports.json(session).contains("NO SCAN PERFORMED"))
        assertEquals(SweepPinKind.TAKEOFF, session.findings.single { it.category == SweepCategory.DRONE }.dronePins.last().kind)
    }

    private fun privateSession(): SweepSession {
        val secret = "$mac $ssid $uas $latitude, $longitude"
        return SweepSession(
            id = "secret-session-token $secret", startedAt = 1_800_000_000_000L, endedAt = 1_800_000_060_000L,
            state = SweepSessionState.COMPLETE,
            findings = listOf(SweepFinding(
                id = "private-key $secret", name = "$ssid $latitude, $longitude", category = SweepCategory.DRONE,
                transport = "BLE", rssi = -71, firstSeenAt = 1_800_000_000_000L, lastSeenAt = 1_800_000_060_000L,
                observations = 12, signatureNames = listOf("Tile Trackers", secret),
                evidence = listOf(SweepEvidence("Advertised name rule", secret), SweepEvidence(secret, secret)),
                nextSteps = listOf("secret-next-step $secret"), identifier = mac, droneId = uas,
                dronePins = listOf(SweepPin(SweepPinKind.AIRCRAFT, latitude, longitude), SweepPin(SweepPinKind.CONTROL_STATION, Double.NaN, longitude)),
                interpretation = "secret-interpretation $secret",
            )),
            coverage = SweepCoverage(wifiStatus = "secret-status $secret", bleStatus = "secret-status $secret", bleSupported = true, bleReady = true, bleSamples = 22, bleExtendedSupported = true),
            phone = "secret-phone $secret", androidVersion = secret, error = "secret-error $secret",
        )
    }
}
