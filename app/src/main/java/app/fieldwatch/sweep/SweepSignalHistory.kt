package app.fieldwatch.sweep

import app.fieldwatch.domain.Rssi
import java.util.TreeMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Measured signal strength only: no distance, movement or safety inference. */
object SweepSignalHistory {
    const val MAX_SAMPLES = 64

    /**
     * Pass the actual fresh Observation.at and Observation.rssi. Never use a
     * delivery time, wall clock fallback or the mapper's possibly sticky RSSI.
     * Old sessions are not backfilled from firstSeenAt/lastSeenAt.
     */
    fun record(
        finding: SweepFinding,
        previous: SweepFinding?,
        observedAt: Long,
        rssi: Int,
        fresh: Boolean = true,
    ): SweepFinding {
        val retained = previous?.takeIf {
            it.id == finding.id && it.transport == finding.transport &&
                it.identifier.equals(finding.identifier, ignoreCase = true)
        }?.signalSamples ?: finding.signalSamples
        return finding.copy(signalSamples = append(retained, observedAt, rssi, fresh))
    }

    /**
     * Keeps the latest timestamps, irrespective of delivery order. A timestamp
     * is one chart point; duplicate/conflicting repeats retain its first value.
     */
    fun append(
        samples: List<SweepSignalSample>,
        observedAt: Long,
        rssi: Int,
        fresh: Boolean = true,
    ): List<SweepSignalSample> {
        val accumulator = SweepSignalAccumulator()
        samples.forEach(accumulator::accept)
        if (fresh) accumulator.accept(SweepSignalSample(observedAt, rssi))
        return accumulator.samples()
    }

    fun bounded(samples: List<SweepSignalSample>): List<SweepSignalSample> =
        SweepSignalAccumulator().apply { samples.forEach(::accept) }.samples()
}

/** Bound retained objects while decoding too, not only after materializing a list. */
internal class SweepSignalAccumulator {
    private val samples = TreeMap<Long, SweepSignalSample>()

    fun accept(sample: SweepSignalSample) {
        if (sample.observedAt <= 0 || !Rssi.measured(sample.rssi)) return
        samples.putIfAbsent(sample.observedAt, sample)
        while (samples.size > SweepSignalHistory.MAX_SAMPLES) samples.pollFirstEntry()
    }

    fun samples(): List<SweepSignalSample> = samples.values.toList()
}

/** Property serializer preserves old JSON compatibility and bounds both disk directions. */
object SweepSignalSamplesSerializer : KSerializer<List<SweepSignalSample>> {
    private val sampleSerializer = SweepSignalSample.serializer()
    private val delegate = ListSerializer(sampleSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<SweepSignalSample>) {
        delegate.serialize(encoder, SweepSignalHistory.bounded(value))
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): List<SweepSignalSample> {
        val accumulator = SweepSignalAccumulator()
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val count = input.decodeCollectionSize(descriptor)
            if (count < 0) throw SerializationException("Signal history length is unavailable")
            for (index in 0 until count) {
                accumulator.accept(input.decodeSerializableElement(descriptor, index, sampleSerializer))
            }
        } else {
            while (true) {
                val index = input.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                accumulator.accept(input.decodeSerializableElement(descriptor, index, sampleSerializer))
            }
        }
        input.endStructure(descriptor)
        return accumulator.samples()
    }
}
