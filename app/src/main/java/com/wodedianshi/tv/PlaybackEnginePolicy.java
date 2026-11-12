package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure decision policy for controlled multi-engine playback fallback. */
public final class PlaybackEnginePolicy {
    private PlaybackEnginePolicy() {}
    public enum Engine { MEDIA3, IJK, LIBVLC }
    public enum FailureKind { TRANSPORT, UNSUPPORTED_CONTAINER_OR_CODEC, DEMUX, DECODER_INITIALIZATION, DECODER_RUNTIME, VIDEO_RENDER, AUDIO_OUTPUT, UNKNOWN }
    public static final class Decision {
        public final boolean changeSource;
        public final boolean tryNextEngine;
        public final Engine nextEngine;
        public final String reason;
        private Decision(boolean source, boolean engine, Engine next, String why) {
            changeSource=source; tryNextEngine=engine; nextEngine=next; reason=why;
        }
    }
    /** Media3 is primary; optional engines are included only after runtime verification. */
    public static List<Engine> orderedEngines(boolean ijkAvailable, boolean libVlcAvailable) {
        List<Engine> engines=new ArrayList<>(); engines.add(Engine.MEDIA3);
        if(ijkAvailable) engines.add(Engine.IJK);
        if(libVlcAvailable) engines.add(Engine.LIBVLC);
        return Collections.unmodifiableList(engines);
    }
    /** Network failures change URL; codec/demux/render failures may change engine. */
    public static Decision decide(FailureKind failure, List<Engine> available, Engine current) {
        if(failure==null||available==null||available.isEmpty()||current==null)
            return new Decision(false,false,null,"invalid playback state");
        if(failure==FailureKind.TRANSPORT)
            return new Decision(true,false,null,"transport failure: try a distinct source");
        if(!requiresEngineFallback(failure))
            return new Decision(false,false,null,"failure is not classified for engine fallback");
        int index=available.indexOf(current);
        if(index<0) return new Decision(false,false,null,"current engine is not available");
        if(index+1>=available.size()) return new Decision(false,false,null,"no compatible engine remains");
        return new Decision(false,true,available.get(index+1),"decoder/demux/render failure: try next verified engine");
    }
    private static boolean requiresEngineFallback(FailureKind f) {
        switch(f) {
            case UNSUPPORTED_CONTAINER_OR_CODEC: case DEMUX: case DECODER_INITIALIZATION:
            case DECODER_RUNTIME: case VIDEO_RENDER: case AUDIO_OUTPUT: return true;
            default: return false;
        }
    }
}
