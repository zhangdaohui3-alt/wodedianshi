package com.wodedianshi.tv;

import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts bounded media/init resources and conservative encryption metadata from HLS playlists. */
public final class HlsMediaPlaylistPolicy {
    private static final int MAX_MEDIA_SEGMENTS = 4;
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*URI=\\\"([^\\\"]+)\\\"");
    private static final Pattern METHOD_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*METHOD=([^,\\s]+)");
    private static final Pattern KEYFORMAT_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*KEYFORMAT=\\\"([^\\\"]+)\\\"");
    private HlsMediaPlaylistPolicy() {}

    public static final class Resources {
        public final String initializationUrl;
        public final boolean unsupportedInitializationByteRange;
        public final boolean aes128Encrypted;
        public final String encryptionKeyUrl;
        public final boolean unsupportedEncryption;
        public final ArrayList<String> mediaUrls;
        Resources(String init, boolean rangeUnsupported, boolean aes128, String keyUrl,
                  boolean encryptionUnsupported, ArrayList<String> media) {
            initializationUrl=init; unsupportedInitializationByteRange=rangeUnsupported;
            aes128Encrypted=aes128; encryptionKeyUrl=keyUrl;
            unsupportedEncryption=encryptionUnsupported; mediaUrls=media;
        }
    }

    public static Resources parse(String playlist, String finalPlaylistUrl) throws Exception {
        ArrayList<String> media = new ArrayList<>();
        String initialization = null, activeKeyUrl = null, aesKeyUrl = null;
        boolean unsupportedMapRange = false, aes128 = false, unsupportedEncryption = false;
        if (playlist == null || finalPlaylistUrl == null)
            return new Resources(null,false,false,null,false,media);
        String[] lines = playlist.replace("\\r", "").split("\\n");
        for (int i=0;i<lines.length && media.size()<MAX_MEDIA_SEGMENTS;i++) {
            String line=lines[i].trim();
            if(line.startsWith("#EXT-X-KEY:")) {
                String attrs=line.substring("#EXT-X-KEY:".length());
                String method=attribute(METHOD_ATTRIBUTE,attrs);
                String format=attribute(KEYFORMAT_ATTRIBUTE,attrs);
                if("NONE".equals(method)) {
                    activeKeyUrl=null;
                } else if("AES-128".equals(method) && (format==null || "identity".equals(format))) {
                    String uri=uriAttribute(attrs);
                    String resolved=resolve(uri,finalPlaylistUrl);
                    if(resolved==null) unsupportedEncryption=true;
                    else {
                        if(aesKeyUrl!=null && !aesKeyUrl.equals(resolved)) unsupportedEncryption=true;
                        aesKeyUrl=resolved; activeKeyUrl=resolved; aes128=true;
                    }
                } else {
                    // SAMPLE-AES, DRM key formats and unknown methods are not
                    // treated as clear media; fail closed during source preflight.
                    unsupportedEncryption=true;
                }
            } else if(line.startsWith("#EXT-X-MAP:")) {
                String attrs=line.substring("#EXT-X-MAP:".length());
                if(attrs.matches(".*(?:^|,)\\s*BYTERANGE\\s*=.*")) {
                    unsupportedMapRange=true; initialization=null;
                } else initialization=resolve(uriAttribute(attrs),finalPlaylistUrl);
            } else if(line.startsWith("#EXT-X-PART:")) {
                String attrs=line.substring("#EXT-X-PART:".length());
                if(!attrs.contains("BYTERANGE=")) {
                    String uri=uriAttribute(attrs);
                    if(uri!=null)addUnique(media,resolve(uri,finalPlaylistUrl));
                }
            } else if(line.startsWith("#EXTINF:")) {
                for(int j=i+1;j<lines.length;j++) {
                    String candidate=lines[j].trim();
                    if(candidate.isEmpty())continue;
                    if(candidate.startsWith("#"))break;
                    addUnique(media,resolve(candidate,finalPlaylistUrl)); i=j; break;
                }
            }
        }
        return new Resources(initialization,unsupportedMapRange,aes128,aesKeyUrl,unsupportedEncryption,media);
    }

    private static String attribute(Pattern pattern,String attributes) {
        Matcher m=pattern.matcher(attributes); return m.find()?m.group(1):null;
    }
    private static String uriAttribute(String attributes) {
        return attribute(URI_ATTRIBUTE,attributes);
    }
    private static String resolve(String uri,String base)throws Exception {
        return uri==null||uri.isEmpty()?null:new URL(new URL(base),uri).toString();
    }
    private static void addUnique(ArrayList<String> values,String value) {
        if(value!=null&&!values.contains(value)&&values.size()<MAX_MEDIA_SEGMENTS)values.add(value);
    }
}
