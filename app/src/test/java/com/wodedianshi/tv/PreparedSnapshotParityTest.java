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
        SnapshotCategoryIndex.LocalMembership localOnly=(SnapshotCategoryIndex.LocalMembership)
            build.invoke(activity,rows,(BooleanSupplier)()->false,false);
        assertEquals(index.candidates(null),localOnly.candidates(null));
        for(String label:new String[]{"广东","福建","江西","湖南","北京","湖北"})
            assertEquals(label,index.candidates(label),localOnly.candidates(label));
        assertNull(localOnly.fixedCategory("央视"));
        assertNull(localOnly.geoKeys("geo:countries:us"));
        // No prepared cache is installed: exercise the production uncached
        // snapshot membership path directly instead of the retired wrapper.
        assertNull(field(MainActivity.class,"preparedLocalSnapshot").get(activity));
        Method geo=method("geoKeysForSnapshot",List.class,long.class,String.class,String.class);
        for(String[] scope:new String[][]{
            {"geo:countries:us","us"},{"geo:countries:cn","cn"},{"geo:countries:gb","gb"},
            {"geo:cities:us-phoenix",""},{"geo:subdivisions:us-az",""},{"geo:subdivisions:us-ca",""}}){
            assertEquals(scope[0],geo.invoke(activity,rows,7L,scope[0],scope[1]),index.geoKeys(scope[0]));
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

    @Test public void pageResultRekeysWithoutRebuildingPublishedCollections() throws Exception {
        Class<?> order=Class.forName("com.wodedianshi.tv.MainActivity$RemoteChannelOrder");
        Constructor<?> constructor=order.getDeclaredConstructor(String.class,ArrayList.class,ArrayList.class);
        constructor.setAccessible(true);
        Class<?> channel=Class.forName("com.wodedianshi.tv.MainActivity$Channel");
        Constructor<?> rowConstructor=channel.getDeclaredConstructor(String.class,String.class,String.class);
        rowConstructor.setAccessible(true);
        Object firstRow=rowConstructor.newInstance("First","https://example.invalid/first","local");
        Object secondRow=rowConstructor.newInstance("Second","https://example.invalid/second","local");
        ArrayList<Integer> indexes=new ArrayList<>(Arrays.asList(5,2));
        ArrayList<Object> visible=new ArrayList<>(Arrays.asList(firstRow,secondRow));
        Object first=constructor.newInstance("old",indexes,visible);
        Method withKey=order.getDeclaredMethod("withKey",String.class);
        withKey.setAccessible(true);
        Object second=withKey.invoke(first,"new");
        assertNotSame(first,second);
        assertEquals("old",field(order,"key").get(first));
        assertEquals("new",field(order,"key").get(second));
        for(String collection:new String[]{"indexes","positions","visible"})
            assertSame(collection,field(order,collection).get(first),field(order,collection).get(second));
        java.util.Map<?,?> positions=(java.util.Map<?,?>)field(order,"positions").get(second);
        assertEquals(Integer.valueOf(0),positions.get(5));
        assertEquals(Integer.valueOf(1),positions.get(2));
        List<?> publishedIndexes=(List<?>)field(order,"indexes").get(second);
        List<?> publishedRows=(List<?>)field(order,"visible").get(second);
        assertEquals(Arrays.asList(5,2),publishedIndexes);
        assertSame(firstRow,publishedRows.get(0));
        assertSame(secondRow,publishedRows.get(1));
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void oppositeRemoteKeysCancelActualActivityRequests() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Class<?> owner=MainActivity.class;
        field(owner,"activityStarted").setBoolean(activity,true);
        field(owner,"fullScreenPlayback").setBoolean(activity,true);
        assertTrue((Boolean)method("canApplyRemoteZap").invoke(activity));
        Class<?> channel=Class.forName("com.wodedianshi.tv.MainActivity$Channel");
        Constructor<?> rowConstructor=channel.getDeclaredConstructor(String.class,String.class,String.class);
        rowConstructor.setAccessible(true);
        String primary="https://example.invalid/primary",backup="https://example.invalid/backup";
        Object first=rowConstructor.newInstance("First",primary,"local");
        Object second=rowConstructor.newInstance("Second","https://example.invalid/second","local");
        @SuppressWarnings("unchecked")
        List<String> sources=(List<String>)field(channel,"backups").get(first);
        sources.add(backup);
        @SuppressWarnings("unchecked")
        List<Object> rows=(List<Object>)field(owner,"channels").get(activity);
        rows.add(first);rows.add(second);
        field(owner,"selected").setInt(activity,0);
        Method stepChannel=method("stepChannel",int.class);
        Method stepSource=method("stepSource",int.class);
        Field channelDispatch=field(owner,"pendingRemoteZapDispatch");
        Field channelSteps=field(owner,"pendingRemoteZapSteps");
        Field channelGeneration=field(owner,"pendingRemoteZapGeneration");
        Field sourceDispatch=field(owner,"pendingRemoteSourceDispatch");
        Field sourceTarget=field(owner,"pendingRemoteSourceUrl");
        Field sourceChannel=field(owner,"pendingRemoteSourceChannel");
        // No directory UI is attached, so a cache miss cannot publish an order.
        stepChannel.invoke(activity,1);
        assertEquals(1,channelSteps.getInt(activity));
        assertNull(channelDispatch.get(activity));
        stepChannel.invoke(activity,-1);
        assertEquals(0,channelSteps.getInt(activity));
        assertEquals(-1L,channelGeneration.getLong(activity));

        // A published order must also cancel its already queued callback.
        Class<?> order=Class.forName("com.wodedianshi.tv.MainActivity$RemoteChannelOrder");
        Constructor<?> orderConstructor=order.getDeclaredConstructor(String.class,ArrayList.class,ArrayList.class);
        orderConstructor.setAccessible(true);
        String orderKey=(String)method("currentDirectoryOrderKey").invoke(activity);
        field(owner,"cachedRemoteChannelOrder").set(activity,orderConstructor.newInstance(orderKey,
            new ArrayList<>(Arrays.asList(0,1)),new ArrayList<>(rows)));
        stepChannel.invoke(activity,1);
        assertNotNull(channelDispatch.get(activity));
        stepChannel.invoke(activity,-1);
        assertNull(channelDispatch.get(activity));
        assertEquals(0,channelSteps.getInt(activity));
        assertEquals(-1L,channelGeneration.getLong(activity));

        stepSource.invoke(activity,1);
        assertEquals(backup,sourceTarget.get(activity));
        assertSame(first,sourceChannel.get(activity));
        assertNotNull(sourceDispatch.get(activity));
        stepSource.invoke(activity,-1);
        assertNull(sourceDispatch.get(activity));
        assertNull(sourceTarget.get(activity));
        assertNull(sourceChannel.get(activity));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(160L,java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0,field(owner,"selected").getInt(activity));
        assertEquals(0,field(channel,"activeSource").getInt(first));
    }
}
