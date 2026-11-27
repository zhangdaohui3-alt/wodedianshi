package com.wodedianshi.tv;

/** Acceptance rules for bounded HLS media-sequence preflight. */
public final class HlsProbeValidationPolicy {
    private HlsProbeValidationPolicy() {}

    /**
     * Once a playlist advertises a second media segment, it must also be
     * retrievable and pass container/decryption validation. This prevents a
     * stale first segment from making a dead live endpoint look healthy.
     */
    public static boolean acceptsSecondSegment(boolean secondSegmentValid) {
        return secondSegmentValid;
    }

    /** A capped sample is not a complete resource, even when its byte count equals the cap. */
    public static boolean isCompleteSample(boolean truncated) {
        return !truncated;
    }

    /** The extra sentinel byte is the proof that the original resource exceeded the retained cap. */
    public static boolean isTruncatedSample(int receivedBytes, int maxBytes) {
        return maxBytes > 0 && receivedBytes > maxBytes;
    }

    private static final java.util.regex.Pattern PREFIX_RANGE=java.util.regex.Pattern
            .compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$");

    /** Parse the actual returned prefix, not an arbitrary partial response.
     * [length, total], with -1 for unknown total. Reject impossible ranges. */
    static long[] parsePrefixRange(String header){
        if(header==null)return null;
        java.util.regex.Matcher match=PREFIX_RANGE.matcher(header.trim());
        if(!match.matches())return null;
        try{
            long start=Long.parseLong(match.group(1)),end=Long.parseLong(match.group(2));
            if(start!=0L||end<0L||end==Long.MAX_VALUE)return null;
            long length=end+1L;
            long total="*".equals(match.group(3))?-1L:Long.parseLong(match.group(3));
            if(total!=-1L&&total<length)return null;
            return new long[]{length,total};
        }catch(NumberFormatException invalid){return null;}
    }

    /** Unknown total never proves resource completeness. */
    static boolean incompletePrefixRange(long[] range,int maxBytes){
        return range==null||maxBytes<=0||range[1]<0L
                ||range[1]>maxBytes||range[0]<range[1];
    }

    /** A valid positive prefix can be deliberately capped; a premature EOF
     * or more bytes than promised is not reliable evidence. */
    static boolean prefixBodyMatches(long[] range,int received,int cap){
        return range!=null&&received>=0&&received<=range[0]
                &&(received==range[0]||received>cap);
    }

    public static boolean isIncompleteContentRange(String contentRange,int maxBytes){
        return incompletePrefixRange(parsePrefixRange(contentRange),maxBytes);
    }
}
