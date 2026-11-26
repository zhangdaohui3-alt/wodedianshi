package com.wodedianshi.tv;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PurgeableCompletionServiceTest {
    @Test public void cancelledQueuedSearchTasksDisappearBeforeNextRequest()throws Exception{
        ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger staleCalls=new AtomicInteger();
        try{
            pool.execute(()->{
                started.countDown();
                try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            PurgeableCompletionService<Integer> old=new PurgeableCompletionService<>(pool);
            List<Future<Integer>> obsolete=new ArrayList<>();
            for(int i=0;i<180;i++)obsolete.add(old.submit(()->staleCalls.incrementAndGet()));
            assertEquals(180,pool.getQueue().size());
            for(Future<Integer> task:obsolete)assertTrue(task.cancel(true));
            assertEquals(0,pool.getQueue().size());
            Set<Future<Integer>> delivered=new HashSet<>();
            for(int i=0;i<180;i++){
                Future<Integer> done=old.poll(1,TimeUnit.SECONDS);
                assertNotNull(done);
                assertTrue(done.isCancelled());
                assertTrue(delivered.add(done));
            }
            assertNull(old.poll());
            PurgeableCompletionService<Integer> next=new PurgeableCompletionService<>(pool);
            Future<Integer> fresh=next.submit(()->42);
            assertEquals(1,pool.getQueue().size());
            release.countDown();
            assertSame(fresh,next.poll(5,TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(42),fresh.get());
            assertEquals(0,staleCalls.get());
            assertNull(next.poll());
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test public void successFailureAndCancellationRemainRequestLocal()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            PurgeableCompletionService<Integer> first=new PurgeableCompletionService<>(pool);
            PurgeableCompletionService<Integer> second=new PurgeableCompletionService<>(pool);
            Future<Integer> success=first.submit(()->7);
            Future<Integer> failure=first.submit(()->{throw new IllegalStateException("test");});
            Future<Integer> other=second.submit(()->{},9);
            Set<Future<Integer>> done=new HashSet<>();
            done.add(first.poll(5,TimeUnit.SECONDS));
            done.add(first.poll(5,TimeUnit.SECONDS));
            assertTrue(done.contains(success));
            assertTrue(done.contains(failure));
            assertEquals(Integer.valueOf(7),success.get());
            try{failure.get();fail("expected task failure");}
            catch(ExecutionException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}
            assertSame(other,second.poll(5,TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(9),other.get());
            assertNull(first.poll());
            assertNull(second.poll());
        }finally{pool.shutdownNow();}
    }
}
