package com.chartplotter;

import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.route.ChartPlotterTrip;
import com.chartplotter.runtime.ChartPlotterRuntime;
import com.chartplotter.runtime.ChartPlotterSailing;
import com.google.inject.Provides;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

@PluginDescriptor(
	name = "Chart Plotter",
	description = "Collision-aware sailing navigation helper for viewing your current and projected courses and planning ordered trips through one or more destinations.",
	configName = "chartplotter",
	tags = {"sailing","sail","path","pathing","plotting","plot","chart","charting","navigation","navigating","nav","map","route","routing","course","ship","boat","minimap","worldmap","destination"}
)
public class ChartPlotterPlugin extends Plugin {
	@Inject private ChartPlotterRuntime runtime;
	@Inject private ChartPlotterSailing sailing;
	@Inject private ChartPlotterRoutes routes;
	@Inject private ConfigManager configManager;
	@Inject private ScheduledExecutorService executor;
	private Future<?> cleanup;
	private boolean migrating;
	@Override
	protected void startUp() {
		migrateConfig(configManager);
		cleanup = executor.submit(() -> {
			try {ChartPlotterMigration.files(new File(RuneLite.RUNELITE_DIR, "chart-plotter"));}
			catch (IOException e) {LoggerFactory.getLogger(ChartPlotterPlugin.class).warn("Unable to remove obsolete sparse routing data", e);}
		});
		runtime.start();
	}
	@Override
	protected void shutDown() {
		if (cleanup != null) cleanup.cancel(false);
		cleanup = null;
		runtime.stop();
	}
	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if ("chartplotter".equals(e.getGroup()) && e.getProfile() == null && migrateConfig(configManager)) runtime.config(e);
	}
	@Subscribe(priority = 1)
	public void onProfileChanged(ProfileChanged ignored) {if (migrateConfig(configManager)) runtime.start();}
	@Subscribe
	public void onVarbitChanged(VarbitChanged e) {runtime.varbit(e);}
	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {runtime.state(e);}
	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded e) {runtime.loaded(e);}
	@Subscribe
	public void onWidgetClosed(WidgetClosed e) {runtime.closed(e);}
	@Subscribe
	public void onMenuOpened(MenuOpened e) {runtime.menu(e);}
	@Subscribe(priority = -1)
	public void onMenuOptionClicked(MenuOptionClicked e) {runtime.menu(e);}
	@Subscribe
	public void onGameTick(GameTick ignored) {runtime.tick();}
	@Subscribe
	public void onPostClientTick(PostClientTick ignored) {runtime.clientTick();}
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e) {runtime.object(e.getGameObject(), true);}
	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e) {runtime.object(e.getGameObject(), false);}
	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e) {runtime.object(e.getWallObject(), true);}
	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e) {runtime.object(e.getWallObject(), false);}
	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned e) {runtime.object(e.getGroundObject(), true);}
	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned e) {runtime.object(e.getGroundObject(), false);}
	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned e) {runtime.object(e.getDecorativeObject(), true);}
	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned e) {runtime.object(e.getDecorativeObject(), false);}
	@Subscribe
	public void onFocusChanged(FocusChanged e) {runtime.focus(e.isFocused());}
	@Subscribe
	public void onPluginMessage(PluginMessage e) {runtime.message(e);}
	@Provides
	public ChartPlotterConfig provideConfig(ConfigManager cm) {
		migrateConfig(cm);
		return cm.getConfig(ChartPlotterConfig.class);
	}
	private synchronized boolean migrateConfig(ConfigManager cm) {
		if (migrating) return false;
		migrating = true;
		try {
			ChartPlotterMigration.config(key -> cm.getConfiguration("chartplotter", key), (key, value) -> {
				if (value == null) cm.unsetConfiguration("chartplotter", key);
				else cm.setConfiguration("chartplotter", key, value);
			});
			return true;
		} finally {migrating = false;}
	}
	public WorldView top() {return sailing.top();}
	public WorldEntity getShip() {return sailing.ship();}
	public LocalPoint anchorLoc(WorldEntity ship) {return sailing.anchorLoc(ship);}
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public boolean isSailing() {return sailing.boarded();}
	public ChartPlotterTrip trip() {return routes.trip();}
	public boolean canAppend() {return routes.canAppend();}
	public ChartPlotterRoutes.Preview coursePreview(int tx, int ty, boolean append) {return routes.preview(tx, ty, append);}
	public void clearCoursePreview() {routes.clearPreview();}
	public boolean suppressPotential(Point m) {return sailing.suppress(m);}
	public boolean courseLine(WorldView wv) {return sailing.courseLine(wv);}
	public int heading(WorldEntity ship) {return sailing.heading(ship);}
	public int course(WorldEntity ship) {return sailing.course(ship);}
	public double speed() {return sailing.speed();}
	public double baseSpeed() {return sailing.baseSpeed();}
	public double accel() {return sailing.accel();}
	public double maxSpeed() {return sailing.maxSpeed();}
	public boolean reversing() {return sailing.reversing();}
	public long motionTime() {return sailing.motionTime();}
}
