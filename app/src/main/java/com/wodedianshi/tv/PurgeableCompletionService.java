package com.wodedianshi.tv;

import java.util.concurrent.*;

/** A request-local completion queue whose submitted Future is also the exact
 * runnable queued by the existing executor. Unlike ExecutorCompletionService's
 * outer QueueingFuture, cancelled queued tasks can be removed immediately.
 * Running tasks still require cooperative interruption; no extra worker. */
final class PurgeableCompletionService<V> implements CompletionService<V> {
    private final Executor executor;
    private final BlockingQueue<Future<V>> completed=new LinkedBlockingQueue<>();

    PurgeableCompletionService(Executor executor){
        this.executor=java.util.Objects.requireNonNull(executor);
    }

    @Override public Future<V> submit(Callable<V> callable){
        FutureTask<V> task=new FutureTask<V>(callable){
            @Override protected void done(){
                if(isCancelled()&&executor instanceof ThreadPoolExecutor)
                    ((ThreadPoolExecutor)executor).remove(this);
                completed.add(this);
            }
        };
        executor.execute(task);
        return task;
    }

    @Override public Future<V> submit(Runnable runnable,V result){
        return submit(Executors.callable(runnable,result));
    }

    @Override public Future<V> take()throws InterruptedException{return completed.take();}
    @Override public Future<V> poll(){return completed.poll();}
    @Override public Future<V> poll(long timeout,TimeUnit unit)throws InterruptedException{
        return completed.poll(timeout,unit);
    }
}
