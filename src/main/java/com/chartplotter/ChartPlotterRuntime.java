package com.chartplotter;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.MouseEvent;
@Singleton
final class ChartPlotterRuntime {
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private OverlayManager overlayManager;
	@Inject private ChartPlotterOverlay overlay;
	@Inject private ChartPlotterMinimapOverlay minimapOverlay;
	@Inject private ChartPlotterWorldMapOverlay worldMapOverlay;
	@Inject private MouseManager mouseManager;
	@Inject private ChartPlotterConfig config;
	@Inject private ChartPlotterCollisionCache collisionCache;
	@Inject private ChartPlotterSailing sailing;
	@Inject private ChartPlotterRoutes routes;
	private boolean collisionActive;
	private boolean editorCacheActive;
	private boolean mouseRegistered;
	private volatile ChartPlotterFeatures features = ChartPlotterFeatures.off();
	private final MouseAdapter mouse = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			Point m = new Point(e.getX(), e.getY());
			if (e.getButton() != MouseEvent.BUTTON1) return e;
			boolean mod = e.isAltDown() || e.isShiftDown() || e.isControlDown();
			if (features.edit && worldMapOverlay.movingNode()) clientThread.invoke(() -> worldMapOverlay.placeNode(m));
			else if (e.isShiftDown() && features.edit) clientThread.invoke(() -> worldMapOverlay.startNodeMove(m));
			else if (e.isAltDown() && features.edit) clientThread.invoke(() -> worldMapOverlay.addNode(m));
			else if (e.isControlDown() && features.routes) clientThread.invoke(() -> chartCourse(m));
			if (!mod && features.routes && !worldMapOverlay.movingNode() && minimapOverlay.overMinimap(m)) clientThread.invoke(() -> sailing.setCourse(m));
			return e;
		}
		@Override
		public MouseEvent mouseReleased(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			return e;
		}
		@Override
		public MouseEvent mouseMoved(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			return e;
		}
		@Override
		public MouseEvent mouseDragged(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			return e;
		}
		@Override
		public MouseEvent mouseExited(MouseEvent e) {
			worldMapOverlay.nodeAlt(false);
			return e;
		}
	};
	void start() {apply();}
	void stop() {
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(worldMapOverlay);
		if (mouseRegistered) {
			mouseManager.unregisterMouseListener(mouse);
			mouseRegistered = false;
		}
		collisionActive = false;
		editorCacheActive = false;
		features = ChartPlotterFeatures.off();
		collisionCache.stop();
		routes.stop();
		sailing.reset();
	}
	void config(ConfigChanged e) {if ("chartplotter".equals(e.getGroup())) apply();}
	void varbit(VarbitChanged e) {
		if (!features.tracking) return;
		sailing.varbit(e);
		if (sailing.boarded()) return;
		routes.clear();
		collision(false, null);
		sailing.clear();
	}
	void state(GameStateChanged e) {
		if (!features.tracking) return;
		if (e.getGameState() == GameState.LOGGED_IN) {
			sailing.sync();
			return;
		}
		sailing.reset();
		routes.clear();
		collision(false, null);
	}
	void loaded(WorldViewLoaded e) {
		if (!features.tracking) return;
		WorldView wv = e.getWorldView();
		if (wv == null || !wv.isTopLevel()) return;
		sailing.loaded(wv);
		capture(wv);
	}
	void menu(MenuOptionClicked e) {
		if (!features.routes) return;
		if (e.getMenuAction() != MenuAction.SET_HEADING && !minimapOverlay.overMinimap(client.getMouseCanvasPosition())) return;
		sailing.setCourse(client.getMouseCanvasPosition());
	}
	void tick() {
		if (!features.tracking || !sailing.boarded()) return;
		WorldEntity ship = sailing.ship();
		if (ship == null) {
			sailing.clear();
			routes.clear();
			collision(false, null);
			return;
		}
		LocalPoint loc = ship.getTargetLocation();
		if (loc == null) loc = ship.getLocalLocation();
		if (loc == null) {
			collision(false, null);
			return;
		}
		WorldView top = sailing.top();
		boolean scene = top != null && sailing.sceneChanged(top);
		if (scene) sailing.scene(ship, loc);
		boolean normal = features.cache(sailing.boarded()) && top != null && top.getYellowClickAction() == Constants.CLICK_ACTION_SET_HEADING;
		boolean started = collision(normal, top);
		if (scene && normal && !started) capture(top);
		if (features.routes && top != null) routes.tick(top, ship, loc);
		sailing.motion(ship, loc, scene);
	}
	private void apply() {
		ChartPlotterFeatures prev = features;
		ChartPlotterFeatures next = ChartPlotterFeatures.of(config);
		features = next;
		if (next.worldOverlay) overlayManager.add(overlay);
		else overlayManager.remove(overlay);
		if (next.minimapOverlay) overlayManager.add(minimapOverlay);
		else overlayManager.remove(minimapOverlay);
		if (next.worldMapOverlay) overlayManager.add(worldMapOverlay);
		else overlayManager.remove(worldMapOverlay);
		if (!next.routes) routes.stop();
		if (next.input && !mouseRegistered) {
			mouseManager.registerMouseListener(mouse);
			mouseRegistered = true;
		} else if (!next.input && mouseRegistered) {
			mouseManager.unregisterMouseListener(mouse);
			mouseRegistered = false;
		}
		if (next.edit && !editorCacheActive) {
			collisionCache.start();
			editorCacheActive = true;
		} else if (!next.edit && editorCacheActive) {
			editorCacheActive = false;
			if (!collisionActive) collisionCache.stop();
		}
		if (!next.cache(sailing.boarded())) collision(false, null);
		if (!next.tracking) sailing.reset();
		else if (!prev.tracking) clientThread.invoke(sailing::sync);
	}
	private void chartCourse(Point m) {
		int[] dst = worldMapOverlay.tile(m);
		if (dst != null) routes.chart(dst[0], dst[1]);
	}
	private boolean collision(boolean active, WorldView top) {
		if (active == collisionActive) return false;
		collisionActive = active;
		if (active) {
			collisionCache.start();
			capture(top);
			return true;
		}
		if (!editorCacheActive) collisionCache.stop();
		return false;
	}
	private void capture(WorldView top) {
		if (!collisionActive && !editorCacheActive) return;
		collisionCache.capture(top);
	}
}
