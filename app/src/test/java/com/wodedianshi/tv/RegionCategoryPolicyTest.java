package com.wodedianshi.tv;
import org.junit.Test;
import static org.junit.Assert.*;
public class RegionCategoryPolicyTest {
 @Test public void recognizesEnglishAndChineseGroups(){
  assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("TVB Jade","Hong Kong"));
  assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("TDM","Macau"));
  assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("TVBS","Taiwan"));
 }
 @Test public void recognizesCountryCodesAndBroadcasters(){
  assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("RTHK","HK"));
  assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("TTV","TW"));
  assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("澳视","MO"));
 }
 @Test public void rejectsUnrelatedChannels(){
  assertFalse(RegionCategoryPolicy.isHongKongMacauTaiwan("CCTV-1","China"));
  assertFalse(RegionCategoryPolicy.isHongKongMacauTaiwan("BBC News","UK"));
 }
}