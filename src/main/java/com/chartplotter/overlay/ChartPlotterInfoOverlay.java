package com.chartplotter.overlay;

import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterEtaMode;
import com.chartplotter.ChartPlotterPlugin;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.route.ChartPlotterTrip;
import com.chartplotter.runtime.ChartPlotterSailing;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;

import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;

public final class ChartPlotterInfoOverlay extends OverlayPanel {
	private final Client client;
	private final ChartPlotterConfig config;
	private final ChartPlotterSailing sailing;
	private final ChartPlotterRoutes routes;
	private Object menuStops;
	private ChartPlotterTrip cachedTrip;
	private long cachedMotion = Long.MIN_VALUE;
	@Inject
	ChartPlotterInfoOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterSailing sailing, ChartPlotterRoutes routes) {
		super(plugin);
		this.client = client;
		this.config = config;
		this.sailing = sailing;
		this.routes = routes;
		setClearChildren(false);
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_LOW);
	}
	@Override
	public List<OverlayMenuEntry> getMenuEntries() {
		ChartPlotterTrip trip = routes.trip();
		Object stops = trip.stopKey();
		if (stops != menuStops) {
			menuStops = stops;
			super.getMenuEntries().clear();
			addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "Chart Plotter info");
			if (trip.size() > 1) addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Skip next stop", "Chart Plotter info", e -> {if (routes.trip().stopKey() == stops) routes.remove(0);});
			if (!trip.empty()) addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Clear trip", "Chart Plotter info", e -> routes.clear());
		}
		return super.getMenuEntries();
	}
	@Override
	public Dimension render(Graphics2D g) {
		if (!sailing.boarded() || client.getGameState() != GameState.LOGGED_IN) return null;
		ChartPlotterTrip trip = routes.trip();
		boolean showProgress = config.infoStopProgress() && trip.totalStops() > 1;
		ChartPlotterEtaMode tripMode = config.infoTripEta();
		ChartPlotterEtaMode stopMode = config.infoStopEta();
		ChartPlotterEtaMode turnMode = config.infoTurnEta();
		boolean showTrip = tripMode != ChartPlotterEtaMode.OFF;
		boolean showStop = stopMode != ChartPlotterEtaMode.OFF && (trip.size() > 1 || stopMode != tripMode);
		boolean showTurn = turnMode != ChartPlotterEtaMode.OFF;
		boolean showSpeed = config.infoBoatSpeed();
		if (!showSpeed && (trip.empty() || !showProgress && !showTrip && !showStop && !showTurn)) return null;
		WorldEntity ship = sailing.ship();
		WorldView top = sailing.top();
		LocalPoint loc = ship == null ? null : ship.getLocalLocation();
		if (loc == null || top == null) return null;
		long motion = sailing.motionTime();
		if (trip != cachedTrip || motion != cachedMotion) {
			cachedTrip = trip;
			cachedMotion = motion;
			panelComponent.getChildren().clear();
			panelComponent.getChildren().add(TitleComponent.builder().text("Chart Plotter").build());
			if (!trip.empty()) {
				double bx = top.getBaseX() + loc.getX() / (double) Perspective.LOCAL_TILE_SIZE;
				double by = top.getBaseY() + loc.getY() / (double) Perspective.LOCAL_TILE_SIZE;
				if (showProgress) panelComponent.getChildren().add(LineComponent.builder().left("Stop").right(trip.stopNumber() + " of " + trip.totalStops()).build());
				if (showTrip) panelComponent.getChildren().add(LineComponent.builder().left("Trip ETA").right(eta(trip, bx, by, true, tripMode)).build());
				if (showStop) panelComponent.getChildren().add(LineComponent.builder().left("Next stop").right(eta(trip, bx, by, false, stopMode)).build());
				if (showTurn) {
					ChartPlotterRoutes.Turn turn = ChartPlotterRoutes.turn(trip.active(), bx, by, sailing.reversing() ? -sailing.speed() : sailing.speed(), sailing.accel(), sailing.maxSpeed(), motion);
					panelComponent.getChildren().add(LineComponent.builder().left("Next turn").right(!turn.valid || turn.end ? "-" : sailing.speed() == 0 ? "Stopped" : turnMode.format(turn.ticks)).build());
				}
			}
			if (showSpeed) panelComponent.getChildren().add(LineComponent.builder().left("Speed").right(Double.toString(sailing.speed())).build());
		}
		return super.render(g);
	}
	public void clear() {
		super.getMenuEntries().clear();
		menuStops = null;
		panelComponent.getChildren().clear();
		cachedTrip = null;
		cachedMotion = Long.MIN_VALUE;
	}
	private String eta(ChartPlotterTrip trip, double bx, double by, boolean total, ChartPlotterEtaMode mode) {
		for (int i = 0; i < (total ? trip.size() : 1); i++) {
			ChartPlotterRoute route = trip.route(i);
			if (route == null) return "Charting course";
			if (route.status != ChartPlotterRoute.OK) return route.text();
		}
		if (sailing.speed() == 0) return "Stopped";
		double speed = sailing.averageSpeed();
		if (sailing.reversing() || speed <= 0) return "-";
		return mode.format(trip.distance(bx, by, total ? trip.size() - 1 : 0) / speed);
	}
}
