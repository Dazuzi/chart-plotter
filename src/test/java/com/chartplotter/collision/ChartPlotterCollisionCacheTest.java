package com.chartplotter.collision;

import org.junit.Test;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChartPlotterCollisionCacheTest {
	@Test
	public void stoppingCancelsThePendingFlushAndShutsDownItsExecutor() {
		ChartPlotterCollisionCache cache = new ChartPlotterCollisionCache();
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		try {
			cache.io = executor;
			ScheduledFuture<?> flush = executor.schedule(() -> {}, 1, TimeUnit.DAYS);
			cache.flushTask = flush;
			cache.stop();
			assertTrue(flush.isCancelled());
			assertTrue(executor.isShutdown());
			assertNull(cache.io);
			assertNull(cache.flushTask);
			cache.stop();
		} finally {executor.shutdownNow();}
	}
}
