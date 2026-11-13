package com.wodedianshi.tv;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

/**
 * Bounded, XXE-safe XMLTV parser.
 *
 * XMLTV timestamps with an explicit +/-HHMM offset are parsed as absolute
 * instants. Timestamps without an offset use the configured broadcaster zone;
 * the default is China Standard Time for this China-first application.
 */
public final class XmlTvParser {
    public static final int DEFAULT_MAX_CHANNELS = 10000;
    public static final int DEFAULT_MAX_PROGRAMMES = 100000;
    public static final long DEFAULT_MAX_DOCUMENT_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_TEXT_LENGTH = 4096;

    private final TimeZone defaultTimeZone;
    private final int maxChannels;
    private final int maxProgrammes;
    private final long maxDocumentBytes;

    public XmlTvParser() {
        this(TimeZone.getTimeZone("Asia/Shanghai"), DEFAULT_MAX_CHANNELS,
                DEFAULT_MAX_PROGRAMMES, DEFAULT_MAX_DOCUMENT_BYTES);
    }

    public XmlTvParser(TimeZone defaultTimeZone, int maxChannels, int maxProgrammes) {
        this(defaultTimeZone, maxChannels, maxProgrammes, DEFAULT_MAX_DOCUMENT_BYTES);
    }

    public XmlTvParser(TimeZone defaultTimeZone, int maxChannels, int maxProgrammes,
                       long maxDocumentBytes) {
        this.defaultTimeZone = (TimeZone) (defaultTimeZone == null
                ? TimeZone.getTimeZone("Asia/Shanghai") : defaultTimeZone.clone());
        this.maxChannels = Math.max(1, maxChannels);
        this.maxProgrammes = Math.max(1, maxProgrammes);
        this.maxDocumentBytes = Math.max(1L, maxDocumentBytes);
    }

    public Catalog parse(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input == null");
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            // Fail closed if the XML implementation cannot disable external entities.
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Handler handler = new Handler(defaultTimeZone, maxChannels, maxProgrammes);
            reader.setContentHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External XML entities are not allowed");
            });
            InputSource source = new InputSource(new BoundedInputStream(input, maxDocumentBytes));
            reader.parse(source);
            return handler.toCatalog();
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Unable to parse safe XMLTV document", error);
        }
    }

    public static final class Catalog {
        public final Map<String, XmlTvChannel> channels;
        public final Map<String, List<XmlTvProgram>> programmesByChannel;
        public final int skippedProgrammes;

        private Catalog(Map<String, XmlTvChannel> channels,
                        Map<String, List<XmlTvProgram>> programmesByChannel,
                        int skippedProgrammes) {
            this.channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
            Map<String, List<XmlTvProgram>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<XmlTvProgram>> entry : programmesByChannel.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<>(entry.getValue())));
            }
            this.programmesByChannel = Collections.unmodifiableMap(copy);
            this.skippedProgrammes = skippedProgrammes;
        }

        public List<XmlTvProgram> programmesFor(String channelId) {
            List<XmlTvProgram> result = programmesByChannel.get(channelId);
            return result == null ? Collections.emptyList() : result;
        }
    }

    private static final class Handler extends DefaultHandler {
        private final TimeZone defaultZone;
        private final int maxChannels;
        private final int maxProgrammes;
        private final Map<String, LinkedHashSet<String>> channels = new LinkedHashMap<>();
        private final Map<String, List<XmlTvProgram>> programmes = new LinkedHashMap<>();
        private String activeElement = "";
        private StringBuilder text = new StringBuilder();
        private String channelId = "";
        private String programmeChannel = "";
        private String programmeStart = "";
        private String programmeStop = "";
        private String programmeTitle = "";
        private String programmeDescription = "";
        private List<String> programmeCategories = new ArrayList<>();
        private boolean inChannel;
        private boolean inProgramme;
        private int programmeCount;
        private int skippedProgrammes;

        Handler(TimeZone defaultZone, int maxChannels, int maxProgrammes) {
            this.defaultZone = defaultZone;
            this.maxChannels = maxChannels;
            this.maxProgrammes = maxProgrammes;
        }

        @Override public void startElement(String uri, String localName, String qName,
                                           Attributes attributes) throws SAXException {
            String name = qName == null ? "" : qName.toLowerCase(Locale.ROOT);
            text.setLength(0);
            activeElement = name;
            if ("channel".equals(name)) {
                inChannel = true;
                channelId = clean(attributes.getValue("id"));
                if (!channelId.isEmpty() && !channels.containsKey(channelId)
                        && channels.size() >= maxChannels) {
                    throw new SAXException("XMLTV channel limit exceeded");
                }
                if (!channelId.isEmpty() && !channels.containsKey(channelId)) {
                    channels.put(channelId, new LinkedHashSet<>());
                }
            } else if ("programme".equals(name)) {
                inProgramme = true;
                programmeChannel = clean(attributes.getValue("channel"));
                programmeStart = clean(attributes.getValue("start"));
                programmeStop = clean(attributes.getValue("stop"));
                programmeTitle = "";
                programmeDescription = "";
                programmeCategories = new ArrayList<>();
            }
        }

        @Override public void characters(char[] ch, int start, int length) {
            if (text.length() < MAX_TEXT_LENGTH) {
                int accepted = Math.min(length, MAX_TEXT_LENGTH - text.length());
                text.append(ch, start, accepted);
            }
        }

        @Override public void endElement(String uri, String localName, String qName)
                throws SAXException {
            String name = qName == null ? "" : qName.toLowerCase(Locale.ROOT);
            String value = clean(text.toString());
            if (inChannel && "display-name".equals(name) && !channelId.isEmpty()
                    && !value.isEmpty()) {
                channels.get(channelId).add(value);
            }
            if (inProgramme) {
                if ("title".equals(name) && programmeTitle.isEmpty()) programmeTitle = value;
                else if ("desc".equals(name) && programmeDescription.isEmpty()) {
                    programmeDescription = value;
                } else if ("category".equals(name) && !value.isEmpty()) {
                    programmeCategories.add(value);
                } else if ("programme".equals(name)) {
                    addProgramme();
                    inProgramme = false;
                }
            }
            if ("channel".equals(name)) inChannel = false;
            activeElement = "";
            text.setLength(0);
        }

        private void addProgramme() throws SAXException {
            if (programmeCount >= maxProgrammes) {
                throw new SAXException("XMLTV programme limit exceeded");
            }
            programmeCount++;
            long start = parseTimestamp(programmeStart, defaultZone);
            long stop = parseTimestamp(programmeStop, defaultZone);
            if (programmeChannel.isEmpty() || programmeTitle.isEmpty()
                    || start < 0 || stop <= start) {
                skippedProgrammes++;
                return;
            }
            XmlTvProgram program = new XmlTvProgram(programmeChannel, start, stop,
                    programmeTitle, programmeDescription, programmeCategories);
            List<XmlTvProgram> channelProgrammes = programmes.get(programmeChannel);
            if (channelProgrammes == null) {
                channelProgrammes = new ArrayList<>();
                programmes.put(programmeChannel, channelProgrammes);
            }
            channelProgrammes.add(program);
        }

        Catalog toCatalog() {
            Map<String, XmlTvChannel> channelRecords = new LinkedHashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : channels.entrySet()) {
                channelRecords.put(entry.getKey(),
                        new XmlTvChannel(entry.getKey(), new ArrayList<>(entry.getValue())));
            }
            for (List<XmlTvProgram> list : programmes.values()) {
                Collections.sort(list, (left, right) ->
                        Long.compare(left.startMillis, right.startMillis));
            }
            return new Catalog(channelRecords, programmes, skippedProgrammes);
        }
    }

    /** Stops oversized feeds even when they contain few enormous text nodes. */
    private static final class BoundedInputStream extends java.io.FilterInputStream {
        private final long maximumBytes;
        private long bytesRead;

        BoundedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override public int read() throws IOException {
            if (bytesRead >= maximumBytes) {
                int extra = in.read();
                if (extra < 0) return -1;
                throw new IOException("XMLTV document byte limit exceeded");
            }
            int value = in.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (bytesRead >= maximumBytes) {
                int extra = in.read();
                if (extra < 0) return -1;
                throw new IOException("XMLTV document byte limit exceeded");
            }
            int allowed = (int) Math.min((long) length, maximumBytes - bytesRead);
            int count = in.read(buffer, offset, allowed);
            if (count > 0) bytesRead += count;
            return count;
        }
    }

    private static long parseTimestamp(String value, TimeZone defaultZone) {
        if (value == null) return -1L;
        String trimmed = value.trim();
        if (!trimmed.matches("\\d{12}(?:\\d{2})?(?:\\s+[+-]\\d{4})?")) return -1L;
        String[] parts = trimmed.split("\\s+");
        String digits = parts[0];
        String pattern = digits.length() == 14 ? "yyyyMMddHHmmss" : "yyyyMMddHHmm";
        TimeZone zone = defaultZone;
        if (parts.length == 2) {
            String offset = parts[1];
            int hours = Integer.parseInt(offset.substring(1, 3));
            int minutes = Integer.parseInt(offset.substring(3, 5));
            if (hours > 14 || minutes > 59 || (hours == 14 && minutes != 0)) return -1L;
            zone = TimeZone.getTimeZone("GMT" + offset);
        }
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setLenient(false);
        format.setTimeZone(zone);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(digits, position);
        return parsed != null && position.getIndex() == digits.length()
                ? parsed.getTime() : -1L;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
