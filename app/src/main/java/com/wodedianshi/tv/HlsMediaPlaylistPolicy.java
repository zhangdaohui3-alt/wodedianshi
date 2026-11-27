package com.wodedianshi.tv;

import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts bounded media/init resources and conservative encryption metadata from HLS playlists. */
public final class HlsMediaPlaylistPolicy {
    private static final int MAX_MEDIA_SEGMENTS = 4;
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*URI=\\\"([^\\\"]+)\\\"");
    private static final Pattern METHOD_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*METHOD=([^,\\s]+)");
    private static final Pattern IV_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*IV=([^,\\s]+)");
    private static final Pattern KEYFORMAT_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*KEYFORMAT=\\\"([^\\\"]+)\\\"");
    private HlsMediaPlaylistPolicy() {}

    public static final class Resources {
        public final String initializationUrl;
        public final boolean unsupportedInitializationByteRange;
        public final boolean unsupportedInitializationMap;
        public final boolean aes128Encrypted;
        public final String encryptionKeyUrl;
        public final String encryptionIv;
        public final long firstMediaSequenceNumber;
        public final boolean unsupportedEncryption;
        public final boolean skippedMediaByteRange;
        boolean requiresUnsupportedPreflight(){
            return unsupportedInitializationByteRange||unsupportedInitializationMap||unsupportedEncryption
                    ||(mediaUrls.isEmpty()&&skippedMediaByteRange);
        }
        public final ArrayList<String> mediaUrls;
        public final ArrayList<Long> mediaSequenceNumbers;
        Resources(String init, boolean rangeUnsupported, boolean mapUnsupported, boolean aes128, String keyUrl,
                  String iv, long firstSequence, boolean encryptionUnsupported, boolean skippedRange, ArrayList<String> media, ArrayList<Long> sequences) {
            initializationUrl=init; unsupportedInitializationByteRange=rangeUnsupported;
            unsupportedInitializationMap=mapUnsupported;
            aes128Encrypted=aes128; encryptionKeyUrl=keyUrl; encryptionIv=iv;
            firstMediaSequenceNumber=firstSequence;
            unsupportedEncryption=encryptionUnsupported; skippedMediaByteRange=skippedRange;
            mediaUrls=media; mediaSequenceNumbers=sequences;
        }
    }

    public static Resources parse(String playlist, String finalPlaylistUrl) throws Exception {
        ArrayList<String> media = new ArrayList<>();
        ArrayList<Long> sequences = new ArrayList<>();
        String initialization = null, activeKeyUrl = null, aesKeyUrl = null, activeIv = null, aesIv = null;
        boolean unsupportedMapRange = false, unsupportedMap = false, aes128 = false, unsupportedEncryption = false;
        boolean activeKeyHasExplicitIv = false, hasFirstSequence = false;
        boolean awaitingSegment=false, segmentGap=false, segmentRange=false, clearMediaSeen=false;
        boolean skippedMediaRange=false;
        long mediaSequence = 0L, nextSequence = 0L, firstSequence = 0L;
        if (playlist == null || finalPlaylistUrl == null)
            return new Resources(null,false,false,false,null,null,0L,false,false,media,sequences);
        String[] lines = playlist.replace("\\r", "").split("\\n");
        URL base=new URL(finalPlaylistUrl);
        // Continue scanning the bounded playlist after the media URL cap is reached: key
        // rotation or METHOD=NONE may appear later and must not be missed.
        for (int i=0;i<lines.length;i++) {
            String line=lines[i].trim();
            if(line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    long parsed=Long.parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    if(parsed<0L)unsupportedEncryption=true;
                    else { mediaSequence=parsed; nextSequence=parsed; }
                } catch(NumberFormatException invalid) { unsupportedEncryption=true; }
            } else if(line.startsWith("#EXT-X-KEY:")) {
                String attrs=line.substring("#EXT-X-KEY:".length());
                String method=attribute(METHOD_ATTRIBUTE,attrs);
                String format=attribute(KEYFORMAT_ATTRIBUTE,attrs);
                if("NONE".equals(method)) {
                    // Resources expose one encryption timeline only; do not probe a mixed
                    // encrypted/clear playlist using a stale key.
                    if(aes128) unsupportedEncryption=true;
                    activeKeyUrl=null; activeIv=null; activeKeyHasExplicitIv=false;
                } else if("AES-128".equals(method) && (format==null || "identity".equals(format))) {
                    if(clearMediaSeen)unsupportedEncryption=true;
                    String uri=uriAttribute(attrs);
                    String resolved=resolve(uri,base);
                    if(resolved==null) unsupportedEncryption=true;
                    else {
                        String iv=attribute(IV_ATTRIBUTE,attrs);
                        if(iv!=null&&!isValidIv(iv))unsupportedEncryption=true;
                        String normalizedIv=iv==null?null:iv.toLowerCase(Locale.ROOT);
                        if(aesKeyUrl!=null && (!aesKeyUrl.equals(resolved)
                                || (aesIv==null?normalizedIv!=null:!aesIv.equals(normalizedIv))))
                            unsupportedEncryption=true;
                        aesKeyUrl=resolved; aesIv=normalizedIv;
                        activeKeyUrl=resolved; activeIv=normalizedIv;
                        activeKeyHasExplicitIv=normalizedIv!=null; aes128=true;
                    }
                } else {
                    // SAMPLE-AES, DRM key formats and unknown methods are not
                    // treated as clear media; fail closed during source preflight.
                    unsupportedEncryption=true;
                }
            } else if(line.startsWith("#EXT-X-MAP:")) {
                String attrs=line.substring("#EXT-X-MAP:".length());
                String mapUrl=resolve(uriAttribute(attrs),base);
                if(mapUrl==null) {
                    unsupportedMap=true; initialization=null;
                    if(attrs.matches(".*(?:^|,)\\s*BYTERANGE\\s*=.*"))unsupportedMapRange=true;
                } else if(attrs.matches(".*(?:^|,)\\s*BYTERANGE\\s*=.*")) {
                    unsupportedMapRange=true; initialization=null;
                } else {
                    if(initialization!=null&&!initialization.equals(mapUrl))unsupportedMap=true;
                    // RFC 8216 requires an explicit IV for an AES-128 encrypted EXT-X-MAP.
                    if(activeKeyUrl!=null&&!activeKeyHasExplicitIv)unsupportedEncryption=true;
                    initialization=mapUrl;
                }
            } else if(line.startsWith("#EXT-X-PART:")) {
                String attrs=line.substring("#EXT-X-PART:".length());
                if(attrs.contains("BYTERANGE="))skippedMediaRange=true;
                if(activeKeyUrl!=null) {
                    // This bounded probe does not map LL-HLS parts to media sequence IVs.
                    unsupportedEncryption=true;
                } else if(!attrs.contains("BYTERANGE=")&&!attrs.matches(".*(?:^|,)\\s*GAP=YES(?:,.*|$)")) {
                    String uri=uriAttribute(attrs);
                    if(uri!=null){
                        clearMediaSeen=true;
                        if(media.size()<MAX_MEDIA_SEGMENTS&&addUnique(media,resolve(uri,base)))sequences.add(-1L);
                    }
                }
            } else if(line.startsWith("#EXTINF:")) {
                awaitingSegment=true;
            } else if(line.equals("#EXT-X-GAP")) {
                segmentGap=true;
            } else if(line.startsWith("#EXT-X-BYTERANGE:")) {
                segmentRange=true;
            } else if(awaitingSegment&&!line.isEmpty()&&!line.startsWith("#")) {
                // Skipped resources still consume a sequence; comments do not.
                if(activeKeyUrl==null)clearMediaSeen=true;
                if(segmentRange&&!segmentGap)skippedMediaRange=true;
                if(!segmentGap&&!segmentRange&&media.size()<MAX_MEDIA_SEGMENTS
                        &&addUnique(media,resolve(line,base))){
                    sequences.add(nextSequence);
                    if(!hasFirstSequence){firstSequence=nextSequence;hasFirstSequence=true;}
                }
                if(nextSequence==Long.MAX_VALUE)unsupportedEncryption=true;
                else nextSequence++;
                awaitingSegment=false;segmentGap=false;segmentRange=false;
            }
        }
        if(aes128&&!hasFirstSequence)unsupportedEncryption=true;
        return new Resources(initialization,unsupportedMapRange,unsupportedMap,aes128,aesKeyUrl,
                aesIv,hasFirstSequence?firstSequence:mediaSequence,unsupportedEncryption,skippedMediaRange,media,sequences);
    }

    /** Only a recognizable open live media window qualifies; an empty
     * closed VOD or an invalid master still follows the ordinary rejection. */
    static boolean isEmptyLiveWindow(String playlist){
        if(playlist==null)return false;
        boolean target=false;
        for(String raw:playlist.replace("\r","").split("\n")){
            String line=raw.trim();
            if(line.equals("#EXT-X-ENDLIST")||line.startsWith("#EXT-X-STREAM-INF:"))return false;
            if(line.startsWith("#EXT-X-TARGETDURATION:")){
                try{target=Long.parseLong(line.substring("#EXT-X-TARGETDURATION:".length()).trim())>0;}
                catch(NumberFormatException malformed){return false;}
            }
        }
        return target;
    }

    private static String attribute(Pattern pattern,String attributes) {
        Matcher m=pattern.matcher(attributes); return m.find()?m.group(1):null;
    }
    private static boolean isValidIv(String iv) {
        return iv!=null&&iv.matches("0[xX][0-9a-fA-F]{32}");
    }
    private static String uriAttribute(String attributes) {
        return attribute(URI_ATTRIBUTE,attributes);
    }
    private static String resolve(String uri,URL base)throws Exception {
        return uri==null||uri.isEmpty()?null:new URL(base,uri).toString();
    }
    private static boolean addUnique(ArrayList<String> values,String value) {
        if(value==null||values.contains(value)||values.size()>=MAX_MEDIA_SEGMENTS)return false;
        values.add(value);return true;
    }
}
