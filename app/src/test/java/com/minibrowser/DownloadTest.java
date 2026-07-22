package com.minibrowser;

import org.junit.Test;
import static org.junit.Assert.*;
import com.minibrowser.concurrency.PriorityThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DownloadTest {
    @Test
    public void testDownloadTaskPriorityComparisons() {
        PriorityThreadPoolExecutor executor = new PriorityThreadPoolExecutor(
                2, 4, 10, TimeUnit.SECONDS, Thread::new);
        
        PriorityThreadPoolExecutor.PriorityRunnable low = 
                new PriorityThreadPoolExecutor.PriorityRunnable(1, () -> {});
        PriorityThreadPoolExecutor.PriorityRunnable high = 
                new PriorityThreadPoolExecutor.PriorityRunnable(10, () -> {});
                
        assertTrue(high.compareTo(low) < 0);
        assertTrue(low.compareTo(high) > 0);
        
        executor.shutdown();
    }
}
