package app.fieldwatch.sweep.network

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Original, offline parser for an owner-supplied generic inventory export.
 *
 * Schema 1: {"schemaVersion":1,"capturedAt":epochMillis,"networkLabel":optional,
 * "devices":[{"hostname":optional,"addresses":[literalAddresses],
 * "macAddress":optional,"reportedIdentifier":optional,"current":optionalBoolean,
 * "lastSeenAt":optionalEpochMillis}]}.
 *
 * All device fields are optional, but each record needs at least one reported
 * name, address, MAC or identifier. Unknown fields, duplicate keys and ambiguous
 * duplicate records are rejected. No router credentials or API calls are used.
 */
object RouterInventoryParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val rootKeys = setOf("schemaVersion", "capturedAt", "networkLabel", "devices")
    private val deviceKeys = setOf("hostname", "addresses", "macAddress", "reportedIdentifier", "current", "lastSeenAt")

    fun parse(bytes: ByteArray, importedAt: Long): RouterInventoryImport {
        require(bytes.isNotEmpty() && bytes.size <= NetworkLimits.MAX_IMPORT_BYTES) { "Choose an inventory JSON file no larger than 1 MiB." }
        require(importedAt > 0) { "The inventory import time is invalid." }
        val contents = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("The inventory file must contain UTF-8 JSON.")
        }
        checkJsonStructure(contents)
        val root = try { json.parseToJsonElement(contents) as? JsonObject } catch (_: SerializationException) { null }
            ?: throw IllegalArgumentException("The inventory file must contain a JSON object.")
        require(root.keys.all { it in rootKeys }) { "The inventory contains unsupported fields. Use the generic schema 1 export." }
        require(integer(root["schemaVersion"], "Inventory schema version") == 1L) { "Only inventory schema version 1 is supported." }
        val capturedAt = integer(root["capturedAt"], "Inventory capture time")
        require(capturedAt > 0 && capturedAt <= importedAt) { "The inventory capture time must be positive and cannot be in the future." }
        val networkLabel = optionalText(root["networkLabel"], "Network label", 120)
        val devices = root["devices"] as? JsonArray
            ?: throw IllegalArgumentException("The inventory needs a devices array.")
        require(devices.size <= NetworkLimits.MAX_ENDPOINTS) { "An inventory can contain up to 128 device records." }
        val addressesSeen = mutableSetOf<String>()
        val macsSeen = mutableSetOf<String>()
        val identifiersSeen = mutableSetOf<String>()
        val recordsSeen = mutableSetOf<List<Any?>>()
        val endpoints = devices.map { element ->
            val device = element as? JsonObject ?: throw IllegalArgumentException("Each inventory device must be an object.")
            require(device.keys.all { it in deviceKeys }) { "An inventory device contains unsupported fields." }
            val hostname = optionalText(device["hostname"], "Hostname", 255)
            val addresses = optionalAddresses(device["addresses"])
            val mac = optionalText(device["macAddress"], "MAC address", 17)?.let(NetworkValidation::mac)
            val identifier = optionalText(device["reportedIdentifier"], "Reported identifier", 128)
            require(hostname != null || addresses.isNotEmpty() || mac != null || identifier != null) { "Each inventory record needs a reported name, address, MAC or identifier." }
            val current = optionalBoolean(device["current"])
            val lastSeenAt = optionalInteger(device["lastSeenAt"], "Router-reported last-seen time")
            require(lastSeenAt == null || (lastSeenAt > 0 && lastSeenAt <= capturedAt)) { "A router-reported last-seen time must be positive and cannot be later than capture time." }
            require(mac == null || macsSeen.add(mac)) { "The inventory contains duplicate reported MAC addresses." }
            require(identifier == null || identifiersSeen.add(identifier)) { "The inventory contains duplicate reported identifiers." }
            // Reused leases cannot unambiguously represent one snapshot. Ask the
            // owner to remove duplicates; never merge them into a device identity.
            require(addresses.all { addressesSeen.add(addressKey(it)) }) { "The inventory contains duplicate or reused address entries. Remove ambiguous leases before importing." }
            val record = listOf(hostname, addresses.sorted(), mac, identifier, current, lastSeenAt)
            require(recordsSeen.add(record)) { "The inventory contains duplicate device records." }
            NetworkEndpoint(
                id = UUID.randomUUID().toString(),
                serviceType = "",
                firstSeenAt = null,
                lastSeenAt = null,
                source = NetworkSource.ROUTER_IMPORT,
                hostnames = hostname?.let(::listOf).orEmpty(),
                addresses = addresses,
                reportedCurrent = current,
                reportedLastSeenAt = lastSeenAt,
                reportedMacAddress = mac,
                reportedIdentifier = identifier,
                importedAt = importedAt,
            )
        }
        return RouterInventoryImport(capturedAt, networkLabel, endpoints)
    }

    private fun optionalAddresses(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        val entries = element as? JsonArray ?: throw IllegalArgumentException("Inventory addresses must be an array of literal addresses.")
        require(entries.size <= NetworkLimits.MAX_ADDRESSES) { "An inventory record has too many addresses." }
        return entries.map { value ->
            val primitive = value as? JsonPrimitive
            require(primitive != null && primitive.isString) { "Inventory addresses must be literal address strings." }
            NetworkAddressValidator.validate(primitive.content)
        }
    }

    private fun optionalText(element: JsonElement?, label: String, limit: Int): String? {
        if (element == null || element == JsonNull) return null
        val primitive = element as? JsonPrimitive
        require(primitive != null && primitive.isString) { "$label must be text." }
        val text = primitive.content.trim()
        return NetworkValidation.text(text, label, limit, false)
    }

    private fun integer(element: JsonElement?, label: String): Long {
        val primitive = element as? JsonPrimitive
        require(primitive != null && !primitive.isString && primitive != JsonNull) { "$label must be an integer in epoch milliseconds." }
        return primitive.longOrNull ?: throw IllegalArgumentException("$label must be an integer in epoch milliseconds.")
    }

    private fun optionalInteger(element: JsonElement?, label: String): Long? =
        if (element == null || element == JsonNull) null else integer(element, label)

    private fun optionalBoolean(element: JsonElement?): Boolean? {
        if (element == null || element == JsonNull) return null
        val primitive = element as? JsonPrimitive
        require(primitive != null && !primitive.isString) { "Router-reported current status must be a boolean." }
        return primitive.booleanOrNull ?: throw IllegalArgumentException("Router-reported current status must be a boolean.")
    }

    /** Canonical comparison of literal IPv6 spelling without doing DNS resolution. */
    private fun addressKey(address: String): String {
        if (!address.contains(':')) return address
        val literal = address.substringBefore('%')
        val scope = address.substringAfter('%', "")
        val sides = literal.split("::", limit = 2)
        fun units(side: String): List<Int> = if (side.isEmpty()) emptyList() else side.split(':').flatMap { part ->
            if (part.contains('.')) {
                val octets = part.split('.').map(String::toInt)
                listOf((octets[0] shl 8) + octets[1], (octets[2] shl 8) + octets[3])
            } else listOf(part.toInt(16))
        }
        val left = units(sides[0])
        val right = if (sides.size == 2) units(sides[1]) else emptyList()
        val full = left + if (sides.size == 2) List(8 - left.size - right.size) { 0 } + right else emptyList()
        return full.joinToString(":") + if (scope.isEmpty()) "" else "%$scope"
    }

    /** Bound nesting before JSON parsing and reject duplicate keys, including escaped equivalents. */
    private fun checkJsonStructure(contents: String) {
        data class Frame(val objectFrame: Boolean, val keys: MutableSet<String> = mutableSetOf(), var wantsKey: Boolean = true)
        val stack = mutableListOf<Frame>()
        var index = 0
        while (index < contents.length) {
            when (contents[index]) {
                '{', '[' -> {
                    require(stack.size < 8) { "The inventory JSON is nested too deeply." }
                    stack.add(Frame(contents[index] == '{'))
                }
                '}', ']' -> {
                    require(stack.isNotEmpty() && stack.last().objectFrame == (contents[index] == '}')) { "The inventory JSON structure is invalid." }
                    stack.removeAt(stack.lastIndex)
                }
                ',' -> if (stack.lastOrNull()?.objectFrame == true) stack.last().wantsKey = true
                '"' -> {
                    val start = index
                    index++
                    while (index < contents.length && contents[index] != '"') {
                        if (contents[index] == '\\') index++
                        index++
                    }
                    require(index < contents.length) { "The inventory JSON contains an unfinished string." }
                    val frame = stack.lastOrNull()
                    if (frame?.objectFrame == true && frame.wantsKey) {
                        val key = try { json.decodeFromString<String>(contents.substring(start, index + 1)) } catch (_: SerializationException) {
                            throw IllegalArgumentException("The inventory JSON contains an invalid field name.")
                        }
                        require(frame.keys.add(key)) { "The inventory JSON contains duplicate fields." }
                        frame.wantsKey = false
                    }
                }
            }
            index++
        }
        require(stack.isEmpty()) { "The inventory JSON structure is incomplete." }
    }
}
