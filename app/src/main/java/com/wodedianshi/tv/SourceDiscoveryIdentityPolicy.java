package com.wodedianshi.tv;

import java.util.Locale;

/** Shared, unchanged source discovery merge identity for staging lookup and worker merging. */
public final class SourceDiscoveryIdentityPolicy {
  private SourceDiscoveryIdentityPolicy(){}
  public static String canonicalName(String name){
    String v=name==null?"":name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_—–·•]","");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:CCTV|中央电视台|央视)-?(\\d{1,2}\\+?|NEWS|4K|8K)").matcher(v);
    if(m.find())return "CCTV-"+m.group(1);
    String[] cn={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
    for(int i=1;i<cn.length;i++)if(v.matches(".*中央(?:电视台)?"+cn[i]+"套.*"))return "CCTV-"+i;
    if(v.contains("中央电视台综合频道"))return "CCTV-1";
    if(v.contains("中央电视台财经频道"))return "CCTV-2";
    if(v.contains("中央电视台综艺频道"))return "CCTV-3";
    if(v.contains("中央电视台中文国际频道"))return "CCTV-4";
    if(v.contains("中央电视台体育频道"))return "CCTV-5";
    if(v.contains("中央电视台电影频道"))return "CCTV-6";
    v=v.replace("超高清","").replace("高清","").replace("超清","").replace("1080P","").replace("720P","").replace("HD","").replace("FHD","").replace("直播","");
    if(v.endsWith("湖南台"))return "湖南卫视";
    if(v.endsWith("浙江台"))return "浙江卫视";
    if(v.endsWith("江苏台"))return "江苏卫视";
    if(v.endsWith("东方台"))return "东方卫视";
    if(v.endsWith("北京台"))return "北京卫视";
    if(v.endsWith("广东台"))return "广东卫视";
    return v;
  }
  public static String identity(String name,String group,String url,String tvgId,String tvgName){
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(name,tvgName,tvgId,group);
    String v=broadcaster.isEmpty()?canonicalName(name):broadcaster;
    String base=v.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
    String fallback=base;
    try{
      java.net.URI u=java.net.URI.create(url==null?"":url);
      String host=u.getHost();
      if(host!=null&&!host.isEmpty())fallback=base+"|host:"+host.toLowerCase(Locale.ROOT);
    }catch(Exception ignored){fallback=base+"|url:"+(url==null?"":url.trim());}
    String localIdentity=ChannelIdentityPolicy.localStationIdentity(name,tvgName);
    return ChannelIdentityPolicy.identity(v,tvgId,fallback,localIdentity);
  }
}
