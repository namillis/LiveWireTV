package com.livewire.tv.feature.providers.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One `get_live_streams` row, with only the fields LiveWire reads. Decoded straight from the
 * response stream: a pool list is 23,000+ rows of 15 fields, and building a JSON tree of all of
 * them first was most of the memory peak on low-RAM TVs. Other fields are skipped.
 */
@Serializable
internal data class XtreamStream(
    @SerialName("stream_id") @Serializable(with = LenientText::class) val streamId: String? = null,
    @Serializable(with = LenientText::class) val name: String? = null,
    @SerialName("stream_icon") @Serializable(with = LenientText::class) val streamIcon: String? = null,
    @SerialName("epg_channel_id") @Serializable(with = LenientText::class) val epgChannelId: String? = null,
    @SerialName("category_id") @Serializable(with = LenientText::class) val categoryId: String? = null,
    @SerialName("tv_archive") @Serializable(with = LenientText::class) val tvArchive: String? = null,
    @SerialName("tv_archive_duration") @Serializable(with = LenientText::class) val tvArchiveDuration: String? = null,
    @Serializable(with = LenientText::class) val num: String? = null,
    /** Kept as JSON so [com.livewire.tv.feature.providers.domain.PoolSignals.parseEvent] reads it as before. */
    @SerialName("pool_event") val poolEvent: JsonElement? = null,
)

/** One `get_live_categories` row. */
@Serializable
internal data class XtreamCategory(
    @SerialName("category_id") @Serializable(with = LenientText::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = LenientText::class) val categoryName: String? = null,
)

/**
 * Reads a field as text whatever JSON type the provider sent: Xtream panels send ids and flags
 * as strings or as numbers (`"187"` or `187`, `"1"` or `1`). Any primitive comes back as its
 * text, null as null, and an object or array as null, matching how the app read these fields
 * from a JSON tree before.
 */
internal object LenientText : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientText", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return (json.decodeJsonElement() as? JsonPrimitive)?.contentOrNull
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
