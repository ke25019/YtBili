package com.ytstyle.bili.data.cast

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The text of the DLNA/UPnP conversation, as pure functions.
 *
 * Discovery is one multicast search, and control is a handful of SOAP calls.
 * Everything that decides *what* to send and *how to read the answer* lives
 * here so it can be unit-tested with fixture strings — the parts that need a
 * socket are kept separate in [SsdpDiscovery] and [DlnaController].
 */
object SsdpProtocol {

    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val MULTICAST_PORT = 1900

    /** A standard MediaRenderer; the 云视听小电视 app answers this. */
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** The search an SSDP client sends. `MX` is the maximum wait, in seconds. */
    fun searchRequest(
        searchTarget: String = MEDIA_RENDERER,
        maxWaitSeconds: Int = 3,
    ): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n")
        // The quotes are part of the header value, not a formatting accident.
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $maxWaitSeconds\r\n")
        append("ST: $searchTarget\r\n")
        append("\r\n")
    }

    /**
     * One device's reply.
     *
     * Only [location] is needed to go further: it points at the description XML
     * that carries the name and the control URL.
     */
    data class SsdpResponse(
        val location: String = "",
        val server: String = "",
        val searchTarget: String = "",
        val usn: String = "",
    )

    /**
     * Reads an SSDP reply.
     *
     * Returns null for anything that is not a 200 with an absolute `LOCATION`:
     * the multicast port also carries NOTIFY advertisements from devices we did
     * not ask about, and a reply we cannot fetch anything from is useless.
     */
    fun parseResponse(raw: String): SsdpResponse? {
        val lines = raw.split("\r\n", "\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        if (!lines.first().trim().uppercase().startsWith("HTTP/1.1 200")) return null

        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }

        val location = headers["location"].orEmpty()
        if (!location.startsWith("http://", ignoreCase = true) &&
            !location.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return SsdpResponse(
            location = location,
            server = headers["server"].orEmpty(),
            searchTarget = headers["st"].orEmpty(),
            usn = headers["usn"].orEmpty(),
        )
    }
}

/** A renderer the app can drive. */
data class DlnaRenderer(
    val name: String = "",
    val descriptionUrl: String = "",
    /** Absolute URL of the AVTransport control endpoint. */
    val controlUrl: String = "",
    val serviceType: String = "",
)

/**
 * Reads a UPnP device description XML.
 *
 * The parser is `javax.xml` rather than a regex on purpose: these documents are
 * namespaced, arbitrarily indented, and sometimes wrap the service list in a
 * device-embedded-device structure. A regex over that is a bug waiting to
 * happen; the DOM walk below just looks for the first AVTransport service.
 */
object DlnaDescription {

    fun parse(xml: String, descriptionUrl: String): DlnaRenderer? {
        val document = parseDocument(xml) ?: return null

        val root = document.documentElement ?: return null
        val name = root.textOfFirst("friendlyName").ifBlank { "DLNA 设备" }

        // Depth-first over every <service>, in document order.
        val services = root.allDescendants("service")
        val avTransport = services.firstOrNull { service ->
            service.textOfFirst("serviceType").contains("AVTransport")
        } ?: return null

        val controlUrl = avTransport.textOfFirst("controlURL")
        if (controlUrl.isBlank()) return null

        return DlnaRenderer(
            name = name,
            descriptionUrl = descriptionUrl,
            controlUrl = resolveUrl(descriptionUrl, controlUrl),
            serviceType = avTransport.textOfFirst("serviceType"),
        )
    }

    /**
     * UPnP control URLs are usually relative to the description document
     * (`/upnp/control/AVTransport1` or similar), so they have to be joined
     * against it.
     */
    fun resolveUrl(base: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        return runCatching { URI(base).resolve(reference).toString() }.getOrDefault(reference)
    }

    private fun Element.allDescendants(localName: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(node: Node) {
            if (node is Element) {
                if (node.localNameOrTag() == localName) out.add(node)
                for (index in 0 until node.childNodes.length) walk(node.childNodes.item(index))
            }
        }
        walk(this)
        return out
    }

    private fun Element.textOfFirst(localName: String): String =
        allDescendants(localName).firstOrNull()?.textContent?.trim().orEmpty()

    private fun Node.localNameOrTag(): String =
        localName ?: nodeName.substringAfter(':')
}

/**
 * Builds and reads the SOAP calls that drive a renderer.
 *
 * Action names and argument names are fixed by the AVTransport spec, so these
 * are string templates; the only interesting part is that argument values have
 * to be XML-escaped (a video title routinely contains `&`).
 */
object SoapEnvelope {

    private const val TRANSPORT_NS = "urn:schemas-upnp-org:service:AVTransport:1"

    fun build(action: String, arguments: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(TRANSPORT_NS).append("\">")
        arguments.forEach { (key, value) ->
            append('<').append(key).append('>')
            append(escape(value))
            append("</").append(key).append('>')
        }
        append("</u:").append(action).append('>')
        append("</s:Body></s:Envelope>")
    }

    /** `SOAPACTION` header value for an action. */
    fun soapActionHeader(action: String): String = "\"$TRANSPORT_NS#$action\""

    /**
     * Flattens a SOAP reply's first body element into name → text.
     *
     * Enough to read `CurrentTransportState`, `RelTime` or a fault code without
     * modelling every action's response type.
     */
    fun parse(xml: String): Map<String, String> {
        val root = parseDocument(xml)?.documentElement ?: return emptyMap()
        val body = root.descendants("Body").firstOrNull() ?: return emptyMap()
        val payload = (0 until body.childNodes.length)
            .mapNotNull { body.childNodes.item(it) as? Element }
            .firstOrNull() ?: return emptyMap()

        val out = HashMap<String, String>()
        for (index in 0 until payload.childNodes.length) {
            val child = payload.childNodes.item(index) as? Element ?: continue
            out[child.localName ?: child.nodeName.substringAfter(':')] =
                child.textContent?.trim().orEmpty()
        }
        return out
    }

    /**
     * A SOAP fault's text, for a log line or a user-facing message.
     *
     * This cannot reuse [parse]: a fault nests its real reason inside
     * `detail/UPnPError/errorCode`, one level below the element [parse] reads.
     */
    fun faultMessage(xml: String): String? {
        val values = allValues(xml)
        val code = values["errorCode"] ?: return null
        val description = values["errorDescription"] ?: "设备拒绝了请求"
        return "$description ($code)"
    }

    /** Every element's text, keyed by local name; first occurrence wins. */
    private fun allValues(xml: String): Map<String, String> {
        val root = parseDocument(xml)?.documentElement ?: return emptyMap()
        val out = HashMap<String, String>()
        fun walk(node: Node) {
            if (node is Element) {
                val name = node.localName ?: node.nodeName.substringAfter(':')
                if (!out.containsKey(name)) {
                    out[name] = node.textContent?.trim().orEmpty()
                }
                for (index in 0 until node.childNodes.length) walk(node.childNodes.item(index))
            }
        }
        walk(root)
        return out
    }

    private fun Element.descendants(localName: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(node: Node) {
            if (node is Element) {
                if ((node.localName ?: node.nodeName.substringAfter(':')) == localName) out.add(node)
                for (index in 0 until node.childNodes.length) walk(node.childNodes.item(index))
            }
        }
        walk(this)
        return out
    }

    fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}

/**
 * Parses an XML document that came from a device on the LAN.
 *
 * The doctype feature is *attempted* rather than required: Android's parser
 * throws `ParserConfigurationException` for features it does not know, and
 * letting that abort the parse would make every television look like it had no
 * description document at all.
 */
private fun parseDocument(xml: String): Document? {
    if (xml.isBlank()) return null
    return runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }.getOrNull()
}

/** The metadata a renderer expects alongside a media URL. */
object DidlLite {

    /**
     * `protocolInfo` has to list the MIME type; renderers reject an item whose
     * fourth field is `*` often enough that the mp4 form is used explicitly.
     */
    fun metadata(url: String, title: String, mimeType: String = "video/mp4"): String =
        buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(SoapEnvelope.escape(title)).append("</dc:title>")
            append("<upnp:class>object.item.videoItem</upnp:class>")
            append("<res protocolInfo=\"http-get:*:").append(mimeType).append(":*\">")
            append(SoapEnvelope.escape(url))
            append("</res>")
            append("</item></DIDL-Lite>")
        }
}
