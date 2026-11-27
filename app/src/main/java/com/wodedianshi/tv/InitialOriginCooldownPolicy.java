package com.wodedianshi.tv;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.net.URL;
import java.util.Locale;

/** Owner-confined, bounded temporary first-request origin health.
 * Never a media validity score or a persisted blacklist. */
final class InitialOriginCooldownPolicy {
    static final long WINDOW_MS=30000L, MAX_COOLDOWN_MS=120000L;
    private static final long RECOVERY_MEMORY_MS=120000L;
    static final int THRESHOLD=3, CAP=128;
    private final LinkedHashMap<String,Health> origins=new LinkedHashMap<>(16,0.75f,true);
    private static final class Health {
        long started;
        final HashSet<String> distinct=new HashSet<>();
        long until;
        int level;
        boolean explicitPressure;
        Health(long now){started=now;}
    }
    static String origin(String address){
        try{
            URL url=new URL(address);
            String scheme=url.getProtocol().toLowerCase(Locale.ROOT);
            String host=url.getHost().toLowerCase(Locale.ROOT);
            if(host.isEmpty()||(!scheme.equals("http")&&!scheme.equals("https")))return "";
            int port=url.getPort()<0?url.getDefaultPort():url.getPort();
            return scheme+"://"+host+":"+port;
        }catch(Exception invalid){return "";}
    }
    /** Expired cooldown is not a ban: retain bounded history for the next
     * recovery episode, but immediately allow requests again. */
    private Health current(String origin,long now){
        Health h=origins.get(origin);
        if(h==null)return null;
        if(now<h.started||(h.until>0L
                ?now>=h.until&&now-h.until>=RECOVERY_MEMORY_MS
                :now-h.started>=WINDOW_MS)){
            origins.remove(origin);return null;
        }
        return h;
    }
    boolean cooling(String origin,long now){
        if(origin==null||origin.isEmpty()||now<0L)return false;
        Health h=current(origin,now);
        return h!=null&&h.until>now;
    }
    /** Recently throttled or repeatedly failing origins must recover through
     * the serial owner, not speculative waves. A reached request clears the
     * recovery history unless explicit active pressure still protects it.
     * This does not prevent an exempt core/replay candidate from being tried. */
    boolean requiresSerialProbe(String origin,long now){
        if(origin==null||origin.isEmpty()||now<0L)return false;
        Health h=current(origin,now);
        return h!=null&&h.level>0;
    }
    private Health episode(String origin,long now){
        Health h=current(origin,now);
        if(h==null){
            h=new Health(now);origins.put(origin,h);
        }else if(h.until>0L&&now>=h.until){
            h.started=now;h.distinct.clear();h.until=0L;h.explicitPressure=false;
        }
        while(origins.size()>CAP)origins.remove(origins.keySet().iterator().next());
        return h;
    }
    private static void cool(Health h,long now){
        h.level=Math.min(3,h.level+1);
        long duration=Math.min(MAX_COOLDOWN_MS,WINDOW_MS<<(h.level-1));
        h.until=now>Long.MAX_VALUE-duration?Long.MAX_VALUE:now+duration;
    }
    void observe(String origin,String endpoint,long now,boolean firstRequestFailed,boolean firstRequestReached){
        if(origin==null||origin.isEmpty()||endpoint==null||endpoint.isEmpty()||now<0L)return;
        if(firstRequestReached){
            Health h=current(origin,now);
            // A successful concurrent request cannot erase explicit active pressure.
            if(h==null||!h.explicitPressure||h.until<=now)origins.remove(origin);
            return;
        }
        if(!firstRequestFailed||cooling(origin,now))return;
        Health h=episode(origin,now);
        if(h.distinct.add(endpoint)&&h.distinct.size()>=THRESHOLD)cool(h,now);
    }
    /** Explicit server throttling is scheduling evidence, not a dead route.
     * Concurrent responses from the same episode do not extend its deadline. */
    void observePressure(String origin,long now){
        if(origin==null||origin.isEmpty()||now<0L)return;
        Health h=episode(origin,now);
        h.explicitPressure=true;
        if(h.until<=now)cool(h,now);
    }
    void clear(){origins.clear();}
    static boolean mayDefer(boolean initial,boolean viewing,boolean coreCoverage,boolean saved,boolean replay){
        return initial&&!viewing&&!coreCoverage&&!saved&&!replay;
    }
}
