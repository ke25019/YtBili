package com.ytstyle.bili.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the DLNA/UPnP text: the search request, the reply parsing, the device
 * description and the SOAP envelopes.
 *
 * Nothing here touches a socket, and the fixtures are hand-written documents in
 * the shapes the spec (and the 云视听小电视 renderer) produce. The parts that
 * actually need a television on the LAN — sending the multicast, posting the
 * SOAP calls — are deliberately not covered by tests.
 */
class DlnaMessagesTest {

    private val descriptionXml = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>客厅的小电视</friendlyName>
            <manufacturer>Bilibili</manufacturer>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/upnp/control/ConnectionManager1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/upnp/control/AVTransport1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `the search request has the headers SSDP requires`() {
        val request = SsdpProtocol.searchRequest()

        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        // The quotes around ssdp:discover are mandatory.
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("MX: 3\r\n"))
        assertTrue(request.contains("ST: ${SsdpProtocol.MEDIA_RENDERER}\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `a media renderer reply yields its description url`() {
        val reply = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.20:49152/description.xml\r\n" +
            "SERVER: Linux/3.14 UPnP/1.0 BilibiliRenderer/1.0\r\n" +
            "ST: ${SsdpProtocol.MEDIA_RENDERER}\r\n" +
            "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

        val parsed = SsdpProtocol.parseResponse(reply)

        assertNotNull(parsed)
        assertEquals("http://192.168.1.20:49152/description.xml", parsed!!.location)
        assertTrue(parsed.server.contains("BilibiliRenderer"))
        assertTrue(parsed.usn.startsWith("uuid:abc"))
    }

    @Test
    fun `header lookup is case insensitive and tolerant of LF only`() {
        val reply = "HTTP/1.1 200 OK\nlocation: http://10.0.0.5:1234/d.xml\n\n"
        assertEquals("http://10.0.0.5:1234/d.xml", SsdpProtocol.parseResponse(reply)?.location)
    }

    @Test
    fun `replies that carry nothing usable are rejected`() {
        listOf(
            "",
            "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n",
            "HTTP/1.1 404 Not Found\r\nLOCATION: http://x/d.xml\r\n\r\n",
            "HTTP/1.1 200 OK\r\nSERVER: x\r\n\r\n",
            // A relative LOCATION cannot be fetched.
            "HTTP/1.1 200 OK\r\nLOCATION: /description.xml\r\n\r\n",
        ).forEach { raw ->
            assertNull("expected null for ${raw.take(30)}", SsdpProtocol.parseResponse(raw))
        }
    }

    @Test
    fun `the description gives the name and an absolute control url`() {
        val renderer = DlnaDescription.parse(
            xml = descriptionXml,
            descriptionUrl = "http://192.168.1.20:49152/description.xml",
        )

        assertNotNull(renderer)
        assertEquals("客厅的小电视", renderer!!.name)
        // The relative controlURL is joined onto the description's host.
        assertEquals(
            "http://192.168.1.20:49152/upnp/control/AVTransport1",
            renderer.controlUrl,
        )
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", renderer.serviceType)
    }

    @Test
    fun `the first AVTransport service wins even behind ConnectionManager`() {
        // ConnectionManager is listed first above; picking it would silently
        // send every playback command to the wrong endpoint.
        val renderer = DlnaDescription.parse(descriptionXml, "http://10.0.0.9:80/d.xml")
        assertTrue(renderer!!.controlUrl.endsWith("AVTransport1"))
    }

    @Test
    fun `a device without AVTransport is not usable`() {
        val noTransport = descriptionXml.replace("AVTransport", "RenderingControl")
        assertNull(DlnaDescription.parse(noTransport, "http://10.0.0.9/d.xml"))
    }

    @Test
    fun `malformed descriptions return null instead of throwing`() {
        assertNull(DlnaDescription.parse("", "http://10.0.0.9/d.xml"))
        assertNull(DlnaDescription.parse("<root", "http://10.0.0.9/d.xml"))
        assertNull(DlnaDescription.parse("not xml at all", "http://10.0.0.9/d.xml"))
    }

    @Test
    fun `relative urls resolve against the description`() {
        assertEquals(
            "http://host:1/upnp/control/x",
            DlnaDescription.resolveUrl("http://host:1/desc/device.xml", "/upnp/control/x"),
        )
        assertEquals(
            "http://host:1/desc/upnp/x",
            DlnaDescription.resolveUrl("http://host:1/desc/device.xml", "upnp/x"),
        )
        assertEquals(
            "https://other:2/abs",
            DlnaDescription.resolveUrl("http://host:1/d.xml", "https://other:2/abs"),
        )
    }

    @Test
    fun `set av transport uri escapes the metadata payload`() {
        val metadata = DidlLite.metadata("http://phone:8080/media?u=x", "标题 & <标签>")
        val envelope = SoapEnvelope.build(
            action = "SetAVTransportURI",
            arguments = mapOf(
                "InstanceID" to "0",
                "CurrentURI" to "http://phone:8080/media?u=x",
                "CurrentURIMetaData" to metadata,
            ),
        )

        assertTrue(envelope.contains("<u:SetAVTransportURI"))
        assertTrue(envelope.contains("urn:schemas-upnp-org:service:AVTransport:1"))
        assertTrue(envelope.contains("<InstanceID>0</InstanceID>"))
        // The DIDL document travels as *text* inside the SOAP body, so its own
        // markup is escaped; the renderer un-escapes it and then parses it as
        // XML, which is why the title ends up double-escaped here.
        assertTrue(envelope.contains("<CurrentURIMetaData>&lt;DIDL-Lite"))
        assertTrue(envelope.contains("&lt;/DIDL-Lite&gt;"))
        // The standalone metadata still carries a correctly escaped title.
        assertTrue(metadata.contains("<dc:title>标题 &amp; &lt;标签&gt;</dc:title>"))
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            SoapEnvelope.soapActionHeader("SetAVTransportURI"),
        )
    }

    @Test
    fun `metadata declares the mime type and the url`() {
        val metadata = DidlLite.metadata("http://phone:8080/media?u=abc", "标题")

        assertTrue(metadata.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
        assertTrue(metadata.contains("http://phone:8080/media?u=abc"))
        assertTrue(metadata.contains("<dc:title>标题</dc:title>"))
    }

    @Test
    fun `a soap reply is flattened into name value pairs`() {
        val response = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentTransportState>PLAYING</CurrentTransportState>
                  <CurrentTransportStatus>OK</CurrentTransportStatus>
                  <CurrentSpeed>1</CurrentSpeed>
                </u:GetTransportInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val parsed = SoapEnvelope.parse(response)

        assertEquals("PLAYING", parsed["CurrentTransportState"])
        assertEquals("OK", parsed["CurrentTransportStatus"])
        assertEquals("1", parsed["CurrentSpeed"])
    }

    @Test
    fun `a soap fault reports its description and code`() {
        val fault = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <s:Fault>
                  <faultcode>s:Client</faultcode>
                  <faultstring>UPnPError</faultstring>
                  <detail>
                    <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                      <errorCode>716</errorCode>
                      <errorDescription>Resource not found</errorDescription>
                    </UPnPError>
                  </detail>
                </s:Fault>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        assertEquals("Resource not found (716)", SoapEnvelope.faultMessage(fault))
        assertNull(SoapEnvelope.faultMessage("<ok/>"))
    }

    @Test
    fun `garbage soap replies parse to nothing`() {
        assertTrue(SoapEnvelope.parse("").isEmpty())
        assertTrue(SoapEnvelope.parse("<not-xml").isEmpty())
        assertNull(SoapEnvelope.faultMessage("<not-xml"))
    }

    @Test
    fun `escape covers the five xml entities`() {
        assertEquals(
            "a&amp;b&lt;c&gt;d&quot;e&apos;f",
            SoapEnvelope.escape("a&b<c>d\"e'f"),
        )
    }
}
