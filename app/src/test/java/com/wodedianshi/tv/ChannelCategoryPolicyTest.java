package com.wodedianshi.tv;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChannelCategoryPolicyTest {
 @Test public void recognizesSatelliteAliases(){
  assertTrue(ChannelCategoryPolicy.isSatellite("湖南卫视高清",""));
  assertTrue(ChannelCategoryPolicy.isSatellite("Hunan TV","China"));
  assertTrue(ChannelCategoryPolicy.isSatellite("Dragon TV",""));
  assertTrue(ChannelCategoryPolicy.isSatellite("浙江卫视","satellite"));
 }
 @Test public void recognizesLocalEntriesInChinaCountryFeeds(){
  assertTrue(ChannelCategoryPolicy.isMainlandLocalCandidate("Shenzhen TV","China"));
  assertTrue(ChannelCategoryPolicy.isMainlandLocalCandidate("厦门综合频道","CN"));
  assertTrue(ChannelCategoryPolicy.isMainlandLocalCandidate("成都新闻综合","中国"));
 }
 @Test public void excludesCctvSatelliteAndRegionalChannels(){
  assertFalse(ChannelCategoryPolicy.isMainlandLocalCandidate("CCTV-1","China"));
  assertFalse(ChannelCategoryPolicy.isMainlandLocalCandidate("Hunan TV","China"));
  assertFalse(ChannelCategoryPolicy.isMainlandLocalCandidate("TVB Jade","Hong Kong"));
 }
 @Test public void unrelatedGroupsAreNotLocal(){
  assertFalse(ChannelCategoryPolicy.isMainlandLocalCandidate("News","US"));
  assertFalse(ChannelCategoryPolicy.isMainlandLocalCandidate("News",""));
 }
}