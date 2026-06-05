package com.chartplotter;

import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.runtime.ChartPlotterRuntime;
import com.chartplotter.runtime.ChartPlotterSailing;
import com.google.inject.Provides;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;

@PluginDescriptor(
	name = "Chart Plotter",
	description = "Displays current course, projected sailing path, and destination routes in the world, minimap, and world map.",
	configName = "chartplotter",
	tags = {"sailing","sail","path","pathing","plotting","plot","chart","charting","navigation","navigating","nav","map","route","routing","course","ship","boat","minimap","worldmap","destination"}
)
public class ChartPlotterPlugin extends Plugin {
	@Inject private ChartPlotterRuntime runtime;
	@Inject private ChartPlotterSailing sailing;
	@Inject private ChartPlotterRoutes routes;
	@Override
	protected void startUp() {runtime.start();}
	@Override
	protected void shutDown() {runtime.stop();}
	@SuppressWarnings("unused")
	@Subscribe
	public void onConfigChanged(ConfigChanged e) {runtime.config(e);}
	@SuppressWarnings("unused")
	@Subscribe
	public void onVarbitChanged(VarbitChanged e) {runtime.varbit(e);}
	@SuppressWarnings("unused")
	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {runtime.state(e);}
	@SuppressWarnings("unused")
	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded e) {runtime.loaded(e);}
	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOpened(MenuOpened e) {runtime.menu(e);}
	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e) {runtime.menu(e);}
	@SuppressWarnings({"unused", "UnusedParameters"})
	@Subscribe
	public void onGameTick(GameTick e) {runtime.tick();}
	@SuppressWarnings("unused")
	@Subscribe
	public void onFocusChanged(FocusChanged e) {runtime.focus(e.isFocused());}
	@SuppressWarnings("unused")
	@Provides
	public ChartPlotterConfig provideConfig(ConfigManager cm) {return cm.getConfig(ChartPlotterConfig.class);}
	public WorldView top() {return sailing.top();}
	public WorldEntity getShip() {return sailing.ship();}
	public LocalPoint anchorLoc(WorldEntity ship) {return sailing.anchorLoc(ship);}
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public boolean isSailing() {return sailing.boarded();}
	public ChartPlotterRoute route() {return routes.route();}
	public ChartPlotterRoutes.Preview coursePreview(int tx, int ty) {return routes.preview(tx, ty);}
	public boolean suppressPotential(Point m) {return sailing.suppress(m);}
	public boolean courseLine(WorldView wv) {return sailing.courseLine(wv);}
	public int heading(WorldEntity ship) {return sailing.heading(ship);}
	public int course(WorldEntity ship) {return sailing.course(ship);}
	public double speed() {return sailing.speed();}
	public double accel() {return sailing.accel();}
	public double maxSpeed() {return sailing.maxSpeed();}
	public boolean reversing() {return sailing.reversing();}
	public long motionTime() {return sailing.motionTime();}
}
