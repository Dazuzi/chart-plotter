package com.chartplotter.route;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.collision.ChartPlotterCollisionData;
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Test;
import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
public class ChartPlotterRoutesTest {
	private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
	private final ChartPlotterCollisionData data = new ChartPlotterCollisionData(ChartPlotterRoutingAudit.open(-8, -8, 24, 8));
	private Runnable onSnapshot;
	private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
		@Override
		public void execute(@Nonnull Runnable task) {getQueue().add(task);}
	};
	private final ChartPlotterRoutes routes = new ChartPlotterRoutes(new ChartPlotterConfig() {}, new ChartPlotterCollisionCache() {
		@Override
		public ChartPlotterCollisionData snapshot() {
			Runnable callback = onSnapshot;
			onSnapshot = null;
			if (callback != null) callback.run();
			return data;
		}
	}, null, new ClientThread() {
		@Override
		public void invoke(Runnable callback) {callbacks.add(callback);}
	}) {
		@Override
		Start startTile() {return new Start(null, -40, 0, 1);}
	};
	@After
	public void stop() {routes.stop(); worker.shutdownNow();}
	@Test
	public void movingAStopReplansItsAdjacentLegsAndKeepsTheOtherLeg() {
		prepare();
		ChartPlotterRoute first = routes.route();
		routes.move(1, 54, 0, 60, 8);
		ChartPlotterTrip moved = routes.trip();
		assertSame(first, moved.route(0));
		assertEquals(ChartPlotterRoute.PENDING, moved.route(1).status);
		assertEquals(ChartPlotterRoute.PENDING, moved.route(2).status);
		assertTrue(moved.route(1).start(14, 0));
		assertTrue(moved.route(2).start(60, 8));
		drain();
		assertSame(first, routes.route());
		assertEquals(60, routes.trip().route(1).tx);
		assertEquals(8, routes.trip().route(1).ty);
		assertEquals(94, routes.trip().route(2).tx);
		for (int i = 1; i < 3; i++) {
			assertEquals(ChartPlotterRoute.OK, routes.trip().route(i).status);
			assertTrue(ChartPlotterRoutingAudit.connectionClear(data, routes.trip().route(i)));
		}
	}
	@Test
	public void completedSearchCannotOverwriteANewerWaypointEdit() {
		prepare();
		ChartPlotterRoute first = routes.route();
		routes.move(1, 54, 0, 60, 8);
		onSnapshot = () -> routes.move(1, 60, 8, 64, 12);
		worker.getQueue().remove().run();
		assertNull(onSnapshot);
		assertEquals(64, routes.trip().x(1));
		assertEquals(12, routes.trip().y(1));
		assertEquals(64, routes.trip().route(1).tx);
		assertEquals(ChartPlotterRoute.PENDING, routes.trip().route(1).status);
		drain();
		assertSame(first, routes.route());
		assertEquals(ChartPlotterRoute.OK, routes.trip().route(1).status);
		assertEquals(64, routes.trip().route(1).tx);
		assertTrue(routes.trip().route(2).start(64, 12));
	}
	@Test
	public void clearingDuringPublicationCancelsWorkAndKeepsTheTripEmpty() {
		prepare();
		routes.move(1, 54, 0, 60, 8);
		Runnable task = worker.getQueue().remove();
		onSnapshot = routes::clear;
		task.run();
		assertNull(onSnapshot);
		assertTrue(routes.trip().empty());
		assertTrue(((Future<?>) task).isCancelled());
		assertTrue(worker.isShutdown());
		assertTrue(worker.getQueue().isEmpty());
	}
	@Test
	public void stoppingCancelsQueuedSearches() {
		routes.exec = worker;
		routes.set(14, 0);
		worker.getQueue().remove().run();
		assertEquals(1, callbacks.size());
		callbacks.remove().run();
		assertFalse(routes.trip().empty());
		Runnable task = worker.getQueue().element();
		routes.stop();
		assertTrue(((Future<?>) task).isCancelled());
		assertTrue(worker.getQueue().isEmpty());
		assertTrue(worker.isShutdown());
		task.run();
		assertTrue(routes.trip().empty());
	}
	@Test
	public void stoppedPlacementCannotStartARouteFromItsClientCallback() {
		routes.exec = worker;
		routes.set(14, 0);
		worker.getQueue().remove().run();
		assertEquals(1, callbacks.size());
		routes.stop();
		callbacks.remove().run();
		assertTrue(routes.trip().empty());
		assertTrue(worker.isShutdown());
		assertNull(routes.exec);
	}
	private void prepare() {
		routes.exec = worker;
		for (int x : new int[]{14, 54, 94}) {routes.append(x, 0); drain();}
		for (int i = 0; i < 3; i++) assertEquals(ChartPlotterRoute.OK, routes.trip().route(i).status);
	}
	private void drain() {
		for (int i = 0; i < 20; i++) {
			Runnable task = worker.getQueue().poll();
			if (task != null) task.run();
			Runnable callback = callbacks.poll();
			if (callback != null) callback.run();
			if (task == null && callback == null) return;
		}
		fail("Routing did not settle within 20 tasks");
	}
}
