package com.chartplotter.runtime;
import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterWorldMapClick;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.overlay.ChartPlotterMinimapOverlay;
import com.chartplotter.overlay.ChartPlotterOverlay;
import com.chartplotter.overlay.ChartPlotterWorldMapOverlay;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.util.ChartPlotterMath;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.Notifier;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;
@Singleton
public final class ChartPlotterRuntime {
	private static final int ALERT_TICKS = 8;
	private static final int CLICK_SLOP = 4;
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
	@Inject private Notifier notifier;
	private boolean collisionActive;
	private boolean editorCacheActive;
	private boolean mouseRegistered;
	private volatile boolean focused = true;
	private int alertX = Integer.MIN_VALUE;
	private int alertY = Integer.MIN_VALUE;
	private int downX;
	private int downY;
	private boolean down;
	private boolean dragged;
	private boolean downCtrl;
	private boolean downAlt;
	private boolean downShift;
	private boolean downBlock;
	private boolean menuBlock;
	private volatile ChartPlotterFeatures features = ChartPlotterFeatures.off();
	private final MouseAdapter mouse = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseCtrl(e.isControlDown());
			if (e.getButton() != MouseEvent.BUTTON1) return e;
			Point m = new Point(e.getX(), e.getY());
			down = true;
			dragged = false;
			downX = e.getX();
			downY = e.getY();
			downCtrl = e.isControlDown();
			downAlt = e.isAltDown();
			downShift = e.isShiftDown();
			downBlock = menuBlock || client.isMenuOpen() || features.edit && worldMapOverlay.movingNode() || e.isAltDown() || e.isShiftDown();
			boolean mod = e.isAltDown() || e.isShiftDown() || e.isControlDown();
			if (features.edit && worldMapOverlay.movingNode()) clientThread.invoke(() -> worldMapOverlay.placeNode(m));
			else if (e.isShiftDown() && features.edit) clientThread.invoke(() -> worldMapOverlay.startNodeMove(m));
			else if (e.isAltDown() && features.edit) clientThread.invoke(() -> worldMapOverlay.addNode(m));
			if (!mod && features.routes && !worldMapOverlay.movingNode() && minimapOverlay.overMinimap(m)) clientThread.invoke(() -> sailing.setCourse(m));
			return e;
		}
		@Override
		public MouseEvent mouseReleased(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseCtrl(e.isControlDown());
			boolean moved = dragged || Math.abs(e.getX() - downX) > CLICK_SLOP || Math.abs(e.getY() - downY) > CLICK_SLOP;
			if (e.getButton() == MouseEvent.BUTTON1 && down && !moved && !downBlock && courseClick()) {
				Point m = new Point(e.getX(), e.getY());
				clientThread.invoke(() -> chartCourse(m));
			}
			if (e.getButton() == MouseEvent.BUTTON1) {
				down = false;
				menuBlock = false;
			}
			return e;
		}
		@Override
		public MouseEvent mouseMoved(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseCtrl(e.isControlDown());
			return e;
		}
		@Override
		public MouseEvent mouseDragged(MouseEvent e) {
			if (down && (Math.abs(e.getX() - downX) > CLICK_SLOP || Math.abs(e.getY() - downY) > CLICK_SLOP)) dragged = true;
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseCtrl(e.isControlDown());
			return e;
		}
		@Override
		public MouseEvent mouseExited(MouseEvent e) {
			down = false;
			worldMapOverlay.nodeAlt(false);
			worldMapOverlay.courseCtrl(false);
			return e;
		}
	};
	public void start() {apply();}
	public void stop() {
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
	public void config(ConfigChanged e) {if ("chartplotter".equals(e.getGroup())) apply();}
	public void varbit(VarbitChanged e) {
		if (!features.tracking) return;
		sailing.varbit(e);
		if (sailing.boarded()) return;
		routes.clear();
		collision(false, null);
		sailing.clear();
	}
	public void state(GameStateChanged e) {
		if (!features.tracking) return;
		if (e.getGameState() == GameState.LOGGED_IN) {
			sailing.sync();
			return;
		}
		if (e.getGameState() == GameState.LOADING) return;
		sailing.reset();
		routes.clear();
		collision(false, null);
	}
	public void loaded(WorldViewLoaded e) {
		if (!features.tracking) return;
		WorldView wv = e.getWorldView();
		if (wv == null || !wv.isTopLevel()) return;
		sailing.loaded(wv);
		capture(wv);
	}
	@SuppressWarnings({"unused", "UnusedParameters"})
	public void menu(MenuOpened e) {
		if (!features.routes || !sailing.boarded()) return;
		Point m = client.getMouseCanvasPosition();
		int[] dst = worldMapOverlay.tile(m);
		if (dst == null) return;
		menuBlock = true;
		ChartPlotterRoute r = routes.route();
		if (r != null && (r.status == ChartPlotterRoute.OK || r.status == ChartPlotterRoute.PENDING)) client.getMenu().createMenuEntry(-1).setOption("Clear destination").setTarget("Chart Plotter").setType(MenuAction.RUNELITE).onClick(me -> routes.clear());
		client.getMenu().createMenuEntry(-1).setOption("Set destination").setTarget("Chart Plotter").setType(MenuAction.RUNELITE).onClick(me -> routes.set(dst[0], dst[1]));
	}
	public void menu(MenuOptionClicked e) {
		if (!features.routes) return;
		Point m = client.getMouseCanvasPosition();
		if (e.getMenuAction() != MenuAction.SET_HEADING && !minimapOverlay.overMinimap(m)) return;
		sailing.setCourse(m);
	}
	public void tick() {
		if (!features.tracking || !sailing.boarded() || client.getGameState() != GameState.LOGGED_IN) return;
		sailing.tick();
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
		boolean normal = features.cache(sailing.boarded()) && top != null;
		boolean started = collision(normal, top);
		if (scene && normal && !started) capture(top);
		if (features.routes && top != null) routes.tick(top, ship, loc);
		sailing.motion(ship, loc, scene);
		alert(top, loc);
	}
	public void focus(boolean focused) {this.focused = focused;}
	private void alert(WorldView top, LocalPoint loc) {
		ChartPlotterRoute r = routes.route();
		if (!config.courseTurnAlert() || top == null || r == null) {
			alertX = Integer.MIN_VALUE;
			alertY = Integer.MIN_VALUE;
			return;
		}
		int bx = ChartPlotterMath.worldTile(top.getBaseX(), loc.getX());
		int by = ChartPlotterMath.worldTile(top.getBaseY(), loc.getY());
		ChartPlotterRoutes.Turn turn = ChartPlotterRoutes.turn(r, bx, by, sailing.speed(), sailing.accel(), sailing.maxSpeed());
		if (!turn.valid || turn.ticks < 0 || turn.ticks > ALERT_TICKS || turn.x == alertX && turn.y == alertY || focused) return;
		notifier.notify("Sailing: next turn approaching");
		alertX = turn.x;
		alertY = turn.y;
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
		if (!features.routes || !sailing.boarded()) return;
		int[] dst = worldMapOverlay.tile(m);
		if (dst != null) routes.chart(dst[0], dst[1]);
	}
	private boolean courseClick() {
		if (!features.routes || !sailing.boarded() || downAlt || downShift) return false;
		ChartPlotterWorldMapClick click = config.worldMapCourseClick();
		return click == ChartPlotterWorldMapClick.CLICK || click == ChartPlotterWorldMapClick.CTRL_CLICK && downCtrl;
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
