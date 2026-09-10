package com.chartplotter.runtime;

import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.route.ChartPlotterRoutes;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChartPlotterRuntimeTest {
	private final ChartPlotterRuntime runtime = new ChartPlotterRuntime();
	private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
	private final List<String> actions = new ArrayList<>();
	@Before
	public void setup() {
		runtime.clientThread = new ClientThread() {
			@Override
			public void invoke(Runnable callback) {callbacks.add(callback);}
		};
		runtime.sailing = new ChartPlotterSailing(null);
		runtime.sailing.boarded = true;
		runtime.features = ChartPlotterFeatures.of(new ChartPlotterConfig() {
			@Override
			public boolean worldChartLine() {return true;}
		});
		runtime.routes = new ChartPlotterRoutes(null, null, null, null) {
			@Override
			public void set(int x, int y) {actions.add("chart " + x + "," + y);}
			@Override
			public void clear() {actions.add("clear");}
		};
	}
	@Test
	public void messagesAcceptOnlyIntegerWorldTilesInThePluginNamespace() {
		runtime.message(new PluginMessage("other", "clear"));
		runtime.message(new PluginMessage("chartplotter", "unknown"));
		runtime.message(new PluginMessage("chartplotter", "chart"));
		for (String key : new String[]{"x", "y"}) for (Object invalid : new Object[]{null, -1, 16384, "1", 1L, 1.0, true}) {
			Map<String, Object> data = new HashMap<>(Map.of("x", 1, "y", 1));
			data.put(key, invalid);
			runtime.message(new PluginMessage("chartplotter", "chart", data));
			assertTrue("key=" + key + " value=" + invalid, callbacks.isEmpty());
		}
		for (int x : new int[]{0, 16383}) runtime.message(new PluginMessage("chartplotter", "chart", Map.of("x", x, "y", 16383 - x)));
		assertTrue(actions.isEmpty());
		assertEquals(2, callbacks.size());
		while (!callbacks.isEmpty()) callbacks.remove().run();
		assertEquals(List.of("chart 0,16383", "chart 16383,0"), actions);
	}
	@Test
	public void chartingRechecksBoardingAndFeaturesWhenTheClientCallbackRuns() {
		PluginMessage chart = new PluginMessage("chartplotter", "chart", Map.of("x", 2700, "y", 3100));
		runtime.message(chart);
		runtime.sailing.boarded = false;
		callbacks.remove().run();
		assertTrue(actions.isEmpty());
		runtime.message(chart);
		runtime.sailing.boarded = true;
		callbacks.remove().run();
		assertEquals(List.of("chart 2700,3100"), actions);
		runtime.message(chart);
		runtime.features = ChartPlotterFeatures.off();
		callbacks.remove().run();
		assertEquals(List.of("chart 2700,3100"), actions);
		runtime.sailing.boarded = false;
		runtime.message(new PluginMessage("chartplotter", "clear"));
		assertEquals(1, actions.size());
		callbacks.remove().run();
		assertEquals(List.of("chart 2700,3100", "clear"), actions);
	}
}
