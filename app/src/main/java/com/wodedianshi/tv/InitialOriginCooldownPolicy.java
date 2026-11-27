package com.wodedianshi.tv;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.net.URL;
import java.util.Locale;

/** Owner-confined, bounded temporary first-request origin health.
 * Never a media validity score or a persisted blacklist. */
final class InitialOriginCooldownPolicy {
    static final long WINDOW_MS=30000L;
    static final int THRESHOLD=3, CAP=128;
    private final LinkedHashMap<String,Health> origins=new LinkedHashMap<>(16,0.75f,true);
    private static final class Health {
        final long started;
        final HashSet<String> distinct=new HashSet<>();
        long until;
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
    boolean cooling(String origin,long now){
        if(origin==null||origin.isEmpty()||now<0L)return false;
        Health h=origins.get(origin);
        if(h==null)return false;
        if(now<h.started||(h.until>0L?now>=h.until:now-h.started>=WINDOW_MS)){
            origins.remove(origin);return false;
        }
        return h.until>now;
    }
    void observe(String origin,String endpoint,long now,boolean firstRequestFailed,boolean firstRequestReached){
        if(origin==null||origin.isEmpty()||endpoint==null||endpoint.isEmpty()||now<0L)return;
        if(firstRequestReached){
            Health current=origins.get(origin);
            // A successful concurrent request must not cancel a recent explicit
            // rate-limit response before its bounded cooling interval expires.
            if(current==null||!current.explicitPressure||!cooling(origin,now))origins.remove(origin);
            return;
        }
        if(!firstRequestFailed||cooling(origin,now))return;
        Health h=origins.get(origin);
        if(h==null){h=new Health(now);origins.put(origin,h);}
        if(h.distinct.add(endpoint)&&h.distinct.size()>=THRESHOLD)
            h.until=now>Long.MAX_VALUE-WINDOW_MS?Long.MAX_VALUE:now+WINDOW_MS;
        while(origins.size()>CAP)origins.remove(origins.keySet().iterator().next());
    }
    /** Explicit server throttling is scheduling evidence, not a dead route. */
    void observePressure(String origin,long now){
        if(origin==null||origin.isEmpty()||now<0L)return;
        Health h=new Health(now);h.explicitPressure=true;
        h.until=now>Long.MAX_VALUE-WINDOW_MS?Long.MAX_VALUE:now+WINDOW_MS;
        origins.put(origin,h);
        while(origins.size()>CAP)origins.remove(origins.keySet().iterator().next());
    }
    void clear(){origins.clear();}
    static boolean mayDefer(boolean initial,boolean viewing,boolean coreCoverage,boolean saved,boolean replay){
        return initial&&!viewing&&!coreCoverage&&!saved&&!replay;
    }
}
