package com.wodedianshi.tv;

import android.app.Application;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public class PreparedSnapshotParityTest {
    private static Field field(Class<?> owner,String name)throws Exception{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static Method method(String name,Class<?>... args)throws Exception{
        Method m=MainActivity.class.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }

    @Test public void actualPreparedMembershipMatchesUncachedFilters() throws Exception {
        // Attach a test activity without running startup, networking or playback.
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Class<?> channel=Class.forName("com.wodedianshi.tv.MainActivity$Channel");
        Constructor<?> constructor=channel.getDeclaredConstructor(String.class,String.class,String.class);
        constructor.setAccessible(true);
        String[][] fixtures={
            {"CCTV-13","央视","CCTV13.cn","china"},
            {"CCTV-2","央视","CCTV2.cn","china"},
            {"湖南卫视","卫视","Hunan.cn","china"},
            {"深圳新闻","地方","ShenzhenNews.cn","china"},
            {"福州新闻","福建","FuzhouNews.cn","china"},
            {"福建 福州新闻","地方","Fuzhou.cn","china"},
            {"江西 抚州新闻","地方","Fuzhou.cn","china"},
            {"香港新闻","港澳台","HKNews.hk","global"},
            {"澳门频道","港澳台","Macau.mo","global"},
            {"台湾新闻","港澳台","Taiwan.tw","global"},
            {"Phoenix Weather","weather","Phoenix.us","global,geo:cities:us-phoenix"},
            {"US Local News","local news","Local.us","global,geo:subdivisions:us-az"},
            {"Sports","sports","Sports.gb","global"},
            {"Movie","movies","Movie.us","global"},
            {"Education","education","Education.ca","global"},
            {"World News","international news","World.fr","global"},
            {"自定义频道","自建频道","","custom"},
            {"Unscoped","","",""}
        };
        List<Object> rows=new ArrayList<>();
        for(int i=0;i<fixtures.length;i++){
            String[] v=fixtures[i];
            Object row=constructor.newInstance(v[0],"https://example.invalid/"+i,v[1]);
            field(channel,"tvgId").set(row,v[2]);
            @SuppressWarnings("unchecked")
            Set<String> scopes=(Set<String>)field(channel,"sourceScopes").get(row);
            if(!v[3].isEmpty())scopes.addAll(Arrays.asList(v[3].split(",")));
            rows.add(row);
        }
        Method build=method("buildLocalMembership",List.class,BooleanSupplier.class,boolean.class);
        SnapshotCategoryIndex.LocalMembership index=(SnapshotCategoryIndex.LocalMembership)
            build.invoke(activity,rows,(BooleanSupplier)()->false,true);
        Method category=method("categoryMatch",channel,String.class);
        Method sort=method("cctvSortOrder",channel);
        Method display=method("displayChannelName",channel);
        String[] categories={"中国","央视","卫视","港澳台","香港","澳门","台湾","全部",
            "全球频道","娱乐频道","国际新闻","体育","天气","教育","自建频道"};
        for(String label:categories){
            List<Integer> expected=new ArrayList<>();
            for(int i=0;i<rows.size();i++)
                if("全部".equals(label)||(Boolean)category.invoke(activity,rows.get(i),label))expected.add(i);
            if("央视".equals(label)){
                // Explicit baseline ordering from the original uncached sort.
                java.util.Map<Integer,Integer> rank=new java.util.HashMap<>();
                java.util.Map<Integer,String> name=new java.util.HashMap<>();
                for(int i:expected){rank.put(i,(Integer)sort.invoke(activity,rows.get(i)));
                    name.put(i,(String)display.invoke(activity,rows.get(i)));}
                expected.sort((a,b)->{int order=Integer.compare(rank.get(a),rank.get(b));
                    return order!=0?order:name.get(a).compareTo(name.get(b));});
            }
            assertEquals(label,expected,index.fixedCategory(label));
        }
        Method local=method("isLocalChannel",channel);
        Method province=method("matchesProvinceLocal",channel,String.class);
        List<Integer> expected=new ArrayList<>();
        for(int i=0;i<rows.size();i++)if((Boolean)local.invoke(activity,rows.get(i)))expected.add(i);
        assertEquals(expected,index.candidates(null));
        for(String label:new String[]{"广东","福建","江西","湖南","北京","湖北"}){
            expected=new ArrayList<>();
            for(int i=0;i<rows.size();i++)if((Boolean)province.invoke(activity,rows.get(i),label))expected.add(i);
            assertEquals(label,expected,index.candidates(label));
        }
        @SuppressWarnings("unchecked")
        List<Object> catalogue=(List<Object>)field(MainActivity.class,"channels").get(activity);
        catalogue.addAll(rows);
        Method geo=method("savedGeoChannelKeys",String.class,String.class);
        for(String[] scope:new String[][]{
            {"geo:countries:us","us"},{"geo:countries:cn","cn"},{"geo:countries:gb","gb"},
            {"geo:cities:us-phoenix",""},{"geo:subdivisions:us-az",""},{"geo:subdivisions:us-ca",""}}){
            assertEquals(scope[0],geo.invoke(activity,scope[0],scope[1]),index.geoKeys(scope[0]));
        }
        // Same-sized replacement must not make the prepared positional data eligible.
        SnapshotCategoryIndex.PreparedLocal prepared=new SnapshotCategoryIndex.PreparedLocal(7,index);
        assertTrue(prepared.matches(7,rows.size()));
        assertFalse(prepared.matches(8,rows.size()));
    }

    @Test public void selectedGeoScopeUsesCurrentRowsAfterSameSizeReplacement() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Class<?> channel=Class.forName("com.wodedianshi.tv.MainActivity$Channel");
        Constructor<?> constructor=channel.getDeclaredConstructor(String.class,String.class,String.class);
        constructor.setAccessible(true);
        Object oldRow=constructor.newInstance("Old Phoenix station","https://example.invalid/old","local");
        Object newRow=constructor.newInstance("New Phoenix station","https://example.invalid/new","local");
        Object other=constructor.newInstance("Other country","https://example.invalid/other","local");
        field(channel,"tvgId").set(oldRow,"OldPhoenix.us");
        field(channel,"tvgId").set(newRow,"NewPhoenix.us");
        field(channel,"tvgId").set(other,"Other.ca");
        for(Object row:Arrays.asList(oldRow,newRow)){
            @SuppressWarnings("unchecked")
            Set<String> scopes=(Set<String>)field(channel,"sourceScopes").get(row);
            scopes.add("global");
            scopes.add("geo:cities:us-phoenix");
        }
        Method key=channel.getDeclaredMethod("key");key.setAccessible(true);
        String oldKey=(String)key.invoke(oldRow),newKey=(String)key.invoke(newRow);
        assertNotEquals(oldKey,newKey);
        List<Object> before=Arrays.asList(oldRow,other),after=Arrays.asList(newRow,other);
        Method build=method("buildLocalMembership",List.class,BooleanSupplier.class,boolean.class);
        Method geo=method("geoKeysForSnapshot",List.class,long.class,String.class,String.class);
        Field prepared=field(MainActivity.class,"preparedLocalSnapshot");
        SnapshotCategoryIndex.LocalMembership oldIndex=(SnapshotCategoryIndex.LocalMembership)
            build.invoke(activity,before,(BooleanSupplier)()->false,true);
        prepared.set(activity,new SnapshotCategoryIndex.PreparedLocal(7,oldIndex));
        for(String[] scope:new String[][]{{"geo:countries:us","us"},{"geo:cities:us-phoenix",""}}){
            assertEquals(Collections.singleton(oldKey),geo.invoke(activity,before,7L,scope[0],scope[1]));
            // The selected scope is unchanged, but the old index is invalid even
            // though the replacement has exactly the same number of rows.
            assertEquals(Collections.singleton(newKey),geo.invoke(activity,after,8L,scope[0],scope[1]));
        }
        SnapshotCategoryIndex.LocalMembership newIndex=(SnapshotCategoryIndex.LocalMembership)
            build.invoke(activity,after,(BooleanSupplier)()->false,true);
        prepared.set(activity,new SnapshotCategoryIndex.PreparedLocal(8,newIndex));
        for(String[] scope:new String[][]{{"geo:countries:us","us"},{"geo:cities:us-phoenix",""}})
            assertEquals(Collections.singleton(newKey),geo.invoke(activity,after,8L,scope[0],scope[1]));
    }

    @Test public void renderedPositionHintCannotSelectDifferentChannelAfterReplacement() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Class<?> channel=Class.forName("com.wodedianshi.tv.MainActivity$Channel");
        Constructor<?> constructor=channel.getDeclaredConstructor(String.class,String.class,String.class);
        constructor.setAccessible(true);
        Object one=constructor.newInstance("One","https://example.invalid/one","local");
        Object two=constructor.newInstance("Two","https://example.invalid/two","local");
        Object three=constructor.newInstance("Three","https://example.invalid/three","local");
        Method key=channel.getDeclaredMethod("key");key.setAccessible(true);
        String oneKey=(String)key.invoke(one),twoKey=(String)key.invoke(two);
        @SuppressWarnings("unchecked")
        List<Object> rows=(List<Object>)field(MainActivity.class,"channels").get(activity);
        rows.add(one);rows.add(two);
        Method lookup=method("findChannelIndexByKey",String.class,int.class);
        assertEquals(0,lookup.invoke(activity,oneKey,0));
        assertEquals(1,lookup.invoke(activity,twoKey,-1));
        rows.clear();rows.add(two);rows.add(one);
        assertEquals(1,lookup.invoke(activity,oneKey,0));
        assertEquals(0,lookup.invoke(activity,twoKey,1));
        rows.set(1,three);
        assertEquals(-1,lookup.invoke(activity,oneKey,1));
        assertEquals(0,lookup.invoke(activity,twoKey,999));
        assertEquals(-1,lookup.invoke(activity,null,0));
    }

    @Test public void pageOrderKeyRejectsChangedSearchAndSameSizeReplacement() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Method order=method("remoteChannelOrderKey",String.class,String.class,boolean.class,
            long.class,long.class,int.class,int.class);
        Object published=order.invoke(activity,"全部","news",false,7L,11L,100,0);
        assertEquals(published,order.invoke(activity,"全部","news",false,7L,11L,100,0));
        assertNotEquals(published,order.invoke(activity,"全部","sports",false,7L,11L,100,0));
        assertNotEquals(published,order.invoke(activity,"全部","news",false,7L,12L,100,0));
        assertNotEquals(published,order.invoke(activity,"全部","news",false,8L,11L,100,0));
        assertNotEquals(published,order.invoke(activity,"央视","news",false,7L,11L,100,0));
    }
}
