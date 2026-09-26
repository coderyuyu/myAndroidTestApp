package com.gpxedt.app.parser

import com.gpxedt.app.model.GpxData
import org.xmlpull.v1.XmlPullParserFactory
import java.io.OutputStream
import java.io.StringWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object GpxSerializer {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Serializes GpxData into standard GPX 1.1 XML and writes to the provided OutputStream.
     */
    fun serialize(gpxData: GpxData, outputStream: OutputStream) {
        val factory = XmlPullParserFactory.newInstance()
        val serializer = factory.newSerializer()
        serializer.setOutput(outputStream, "UTF-8")
        writeGpxXml(serializer, gpxData)
    }

    /**
     * Serializes GpxData to an XML String.
     */
    fun serializeToString(gpxData: GpxData): String {
        val writer = StringWriter()
        val factory = XmlPullParserFactory.newInstance()
        val serializer = factory.newSerializer()
        serializer.setOutput(writer)
        writeGpxXml(serializer, gpxData)
        return writer.toString()
    }

    private fun writeGpxXml(serializer: org.xmlpull.v1.XmlSerializer, gpxData: GpxData) {
        serializer.startDocument("UTF-8", true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

        val ns = "http://www.topografix.com/GPX/1/1"
        serializer.startTag("", "gpx")
        serializer.attribute("", "version", "1.1")
        serializer.attribute("", "creator", "GPX Editor Android")
        serializer.attribute("", "xmlns", ns)
        serializer.attribute("", "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        serializer.attribute("", "xsi:schemaLocation", "$ns $ns/gpx.xsd")

        // <metadata>
        serializer.startTag("", "metadata")
        val trackName = gpxData.name ?: "Exported Track"
        serializer.startTag("", "name")
        serializer.text(trackName)
        serializer.endTag("", "name")

        serializer.startTag("", "time")
        serializer.text(timeFormatter.format(Instant.now()))
        serializer.endTag("", "time")
        serializer.endTag("", "metadata")

        // <wpt> elements
        for (wpt in gpxData.waypoints) {
            serializer.startTag("", "wpt")
            serializer.attribute("", "lat", String.format(Locale.US, "%.7f", wpt.lat))
            serializer.attribute("", "lon", String.format(Locale.US, "%.7f", wpt.lon))

            if (wpt.ele != null) {
                serializer.startTag("", "ele")
                serializer.text(String.format(Locale.US, "%.2f", wpt.ele))
                serializer.endTag("", "ele")
            }

            if (wpt.time != null) {
                serializer.startTag("", "time")
                serializer.text(timeFormatter.format(wpt.time))
                serializer.endTag("", "time")
            }

            serializer.startTag("", "name")
            serializer.text(wpt.name)
            serializer.endTag("", "name")

            if (!wpt.desc.isNullOrEmpty()) {
                serializer.startTag("", "desc")
                serializer.text(wpt.desc)
                serializer.endTag("", "desc")
            }

            if (!wpt.sym.isNullOrEmpty()) {
                serializer.startTag("", "sym")
                serializer.text(wpt.sym)
                serializer.endTag("", "sym")
            }

            serializer.endTag("", "wpt")
        }

        // <trk> element
        if (gpxData.trackPoints.isNotEmpty()) {
            serializer.startTag("", "trk")
            serializer.startTag("", "name")
            serializer.text(trackName)
            serializer.endTag("", "name")

            serializer.startTag("", "trkseg")
            for (pt in gpxData.trackPoints) {
                serializer.startTag("", "trkpt")
                serializer.attribute("", "lat", String.format(Locale.US, "%.7f", pt.lat))
                serializer.attribute("", "lon", String.format(Locale.US, "%.7f", pt.lon))

                if (pt.ele != null) {
                    serializer.startTag("", "ele")
                    serializer.text(String.format(Locale.US, "%.2f", pt.ele))
                    serializer.endTag("", "ele")
                }

                if (pt.time != null) {
                    serializer.startTag("", "time")
                    serializer.text(timeFormatter.format(pt.time))
                    serializer.endTag("", "time")
                }

                serializer.endTag("", "trkpt")
            }
            serializer.endTag("", "trkseg")
            serializer.endTag("", "trk")
        }

        serializer.endTag("", "gpx")
        serializer.endDocument()
    }
}
