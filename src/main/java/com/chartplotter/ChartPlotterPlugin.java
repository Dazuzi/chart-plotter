package com.chartplotter;
import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
@PluginDescriptor(
	name = "Chart Plotter",
	description = "A Chart Plotter helper too to assist you with all your sailing chart plotting needs.",
	configName = "chartplotter",
	tags = {"sailing","sail","heading","navigation","chart","plotter"}
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
	public void onMenuOptionClicked(MenuOptionClicked e) {runtime.menu(e);}
	@SuppressWarnings({"unused", "UnusedParameters"})
	@Subscribe
	public void onGameTick(GameTick e) {runtime.tick();}
	@SuppressWarnings("unused")
	@Provides
	ChartPlotterConfig provideConfig(ConfigManager cm) {return cm.getConfig(ChartPlotterConfig.class);}
	WorldView top() {return sailing.top();}
	WorldEntity getShip() {return sailing.ship();}
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isSailing() {return sailing.boarded();}
	ChartPlotterRoute route() {return routes.route();}
	boolean suppressPotential(Point m) {return sailing.suppress(m);}
	int heading(WorldEntity ship) {return sailing.heading(ship);}
	int course(WorldEntity ship) {return sailing.course(ship);}
}
