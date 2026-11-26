package com.wodedianshi.tv;

import android.app.Application;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public class LocalDirectoryFactsTest {
    @Test public void localFactsAreBoundToExactMetadataAndScopeNotPosition() throws Exception {
        Class<?> channel=Class.forName("com.wodedianshi.tv.MainActivity$Channel");
        Class<?> facts=Class.forName("com.wodedianshi.tv.MainActivity$LocalDirectoryFacts");
        Constructor<?> channelConstructor=channel.getDeclaredConstructor(String.class,String.class,String.class);
        channelConstructor.setAccessible(true);
        Object row=channelConstructor.newInstance("深圳新闻","https://example.invalid/live","地方");
        Constructor<?> factsConstructor=facts.getDeclaredConstructor(String.class,String.class,String.class,String.class,
                boolean.class,String.class,String.class,boolean.class,boolean.class);
        factsConstructor.setAccessible(true);
        Object saved=factsConstructor.newInstance("深圳新闻","地方","","",true,"深圳新闻 地方","广东",false,true);
        Method matches=facts.getDeclaredMethod("matches",channel,boolean.class);
        matches.setAccessible(true);
        assertEquals(Boolean.TRUE,matches.invoke(saved,row,true));
        assertEquals(Boolean.FALSE,matches.invoke(saved,row,false));
        for(String field:new String[]{"name","group","tvgName","tvgId"}){
            Field metadata=channel.getDeclaredField(field);
            metadata.setAccessible(true);
            Object original=metadata.get(row);
            metadata.set(row,"changed");
            assertEquals(field,Boolean.FALSE,matches.invoke(saved,row,true));
            metadata.set(row,original);
            assertEquals(field,Boolean.TRUE,matches.invoke(saved,row,true));
        }
        Field cache=channel.getDeclaredField("localDirectoryFacts");
        cache.setAccessible(true);
        cache.set(row,saved);
        Object replacement=channelConstructor.newInstance("深圳新闻","https://example.invalid/live","地方");
        assertNull(cache.get(replacement));
    }
}
