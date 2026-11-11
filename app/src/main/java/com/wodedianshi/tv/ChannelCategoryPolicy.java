package com.wodedianshi.tv;
import java.util.Locale;
/** Mainland satellite and local-channel classification shared by TV shelves. */
public final class ChannelCategoryPolicy {
 private ChannelCategoryPolicy(){}
 public static boolean isSatellite(String name,String group){
  String all=norm(name)+" "+norm(group);
  if(all.contains("卫视")||all.contains("卫星")||all.contains("satellite"))return true;
  String[] aliases={"hunan tv","hunan satellite","zhejiang tv","zhejiang satellite","jiangsu tv","jiangsu satellite","dragon tv","oriental satellite","beijing satellite","btv satellite","guangdong satellite","anhui satellite","fujian satellite","henan satellite","hubei satellite","sichuan satellite","chongqing satellite","shandong satellite","liaoning satellite","jilin satellite","heilongjiang satellite","jiangxi satellite","shanxi satellite","shaanxi satellite","gansu satellite","yunnan satellite","guizhou satellite","guangxi satellite","tianjin satellite","inner mongolia satellite","xinjiang satellite","qinghai satellite","ningxia satellite","tibet satellite","hainan satellite"};
  for(String a:aliases)if(all.contains(a))return true;
  return false;
 }
 public static boolean isMainlandLocalCandidate(String name,String group){
  String all=norm(name)+" "+norm(group),g=norm(group);
  if(isSatellite(name,group)||all.contains("cctv")||all.contains("央视")||all.contains("中央电视台"))return false;
  if(all.contains("香港")||all.contains("澳门")||all.contains("台湾")||all.contains("hong kong")||all.contains("macau")||all.contains("taiwan"))return false;
  return g.equals("china")||g.equals("cn")||g.equals("中国")||g.equals("中国大陆")||g.equals("mainland china")||g.equals("china mainland")||g.equals("country:china")||g.equals("local")||g.equals("地方");
 }
 private static String norm(String v){return v==null?"":v.toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ').replaceAll("\\s+"," ").trim();}
}