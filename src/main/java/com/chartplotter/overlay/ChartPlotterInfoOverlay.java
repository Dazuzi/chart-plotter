package com.chartplotter.overlay;

import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterEtaMode;
import com.chartplotter.ChartPlotterPlugin;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.route.ChartPlotterTrip;
import com.chartplotter.runtime.ChartPlotterSailing;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;

public final class ChartPlotterInfoOverlay extends OverlayPanel {
	private final Client client;
	private final ChartPlotterConfig config;
	private final ChartPlotterSailing sailing;
	private final ChartPlotterRoutes routes;
	private ChartPlotterTrip cachedTrip;
	private long cachedMotion = Long.MIN_VALUE;
	private String stopProgress;
	private String tripEta;
	private String stopEta;
	private String turnEta;
	private String boatSpeed;
	@Inject
	ChartPlotterInfoOverlay(Client client, ChartPlotterPlugin plugin, ChartPlotterConfig config, ChartPlotterSailing sailing, ChartPlotterRoutes routes) {
		super(plugin);
		this.client = client;
		this.config = config;
		this.sailing = sailing;
		this.routes = routes;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_LOW);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "Chart Plotter info");
	}
	@Override
	public Dimension render(Graphics2D g) {
		if (!sailing.boarded() || client.getGameState() != GameState.LOGGED_IN) return null;
		boolean showProgress = config.infoStopProgress();
		ChartPlotterEtaMode tripMode = config.infoTripEta();
		ChartPlotterEtaMode stopMode = config.infoStopEta();
		ChartPlotterEtaMode turnMode = config.infoTurnEta();
		boolean showTrip = tripMode != ChartPlotterEtaMode.OFF;
		boolean showStop = stopMode != ChartPlotterEtaMode.OFF;
		boolean showTurn = turnMode != ChartPlotterEtaMode.OFF;
		boolean showSpeed = config.infoBoatSpeed();
		ChartPlotterTrip trip = routes.trip();
		if (!showSpeed && (trip.empty() || !showProgress && !showTrip && !showStop && !showTurn)) return null;
		WorldEntity ship = sailing.ship();
		WorldView top = sailing.top();
		LocalPoint loc = ship == null ? null : ship.getLocalLocation();
		if (loc == null || top == null) return null;
		long motion = sailing.motionTime();
		if (trip != cachedTrip || motion != cachedMotion) {
			cachedTrip = trip;
			cachedMotion = motion;
			double bx = top.getBaseX() + loc.getX() / (double) Perspective.LOCAL_TILE_SIZE;
			double by = top.getBaseY() + loc.getY() / (double) Perspective.LOCAL_TILE_SIZE;
			if (!trip.empty()) {
				if (showProgress) stopProgress = trip.stopNumber() + " of " + trip.totalStops();
				if (showTrip) tripEta = eta(trip, bx, by, true, tripMode);
				if (showStop) stopEta = eta(trip, bx, by, false, stopMode);
				if (showTurn) {
					ChartPlotterRoutes.Turn turn = ChartPlotterRoutes.turn(trip.active(), bx, by, sailing.reversing() ? -sailing.speed() : sailing.speed(), sailing.accel(), sailing.maxSpeed(), motion);
					turnEta = !turn.valid || turn.end ? "-" : sailing.speed() == 0 ? "Stopped" : time(turn.ticks, turnMode);
				}
			}
			if (showSpeed) boatSpeed = Double.toString(sailing.speed());
		}
		panelComponent.getChildren().add(TitleComponent.builder().text("Chart Plotter").build());
		if (!trip.empty()) {
			if (showProgress) panelComponent.getChildren().add(LineComponent.builder().left("Stop").right(stopProgress).build());
			if (showTrip) panelComponent.getChildren().add(LineComponent.builder().left("Trip ETA").right(tripEta).build());
			if (showStop) panelComponent.getChildren().add(LineComponent.builder().left("Next stop").right(stopEta).build());
			if (showTurn) panelComponent.getChildren().add(LineComponent.builder().left("Next turn").right(turnEta).build());
		}
		if (showSpeed) panelComponent.getChildren().add(LineComponent.builder().left("Speed").right(boatSpeed).build());
		return super.render(g);
	}
	public void clear() {
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
		return time(trip.distance(bx, by, total) / speed, mode);
	}
	private static String time(double ticks, ChartPlotterEtaMode mode) {
		if (!Double.isFinite(ticks) || ticks < 0) return "-";
		if (mode == ChartPlotterEtaMode.TICKS) return (long) Math.ceil(ticks) + "t";
		long seconds = (long) Math.ceil(ticks * Constants.GAME_TICK_LENGTH / 1000);
		return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
	}
}
