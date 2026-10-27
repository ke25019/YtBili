package com.ytstyle.bili.data.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a field that Bilibili sometimes sends as a string and sometimes as an
 * object.
 *
 * `nav.data.vip_label` is the motivating case: anonymous responses omit it, but
 * a signed-in response returns
 * `{"path": "...", "text": "...", ...}`. Declaring it as `String` made the whole
 * `/x/web-interface/nav` response fail to deserialise with
 * *"Expected beginning of the string, but got {"* — which surfaced as a failed
 * login even though the session cookies had already been stored successfully.
 *
 * Rather than guess per field, anything that can drift in shape uses this:
 * a string is taken as-is, an object contributes its `text` (the human-readable
 * part), and anything else becomes an empty string. A cosmetic field can then
 * never take down a whole response.
 */
object FlexibleTextSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        // Non-JSON decoders have no element to inspect.
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull
                ?: element["content"]?.jsonPrimitive?.contentOrNull
                ?: element["label"]?.jsonPrimitive?.contentOrNull
                ?: ""
            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}
