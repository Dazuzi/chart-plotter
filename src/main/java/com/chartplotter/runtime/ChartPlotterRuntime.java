package com.chartplotter.runtime;

import com.chartplotter.ChartPlotterConfig;
import com.chartplotter.ChartPlotterWorldMapClick;
import com.chartplotter.collision.ChartPlotterCollisionCache;
import com.chartplotter.overlay.ChartPlotterMinimapOverlay;
import com.chartplotter.overlay.ChartPlotterOverlay;
import com.chartplotter.overlay.ChartPlotterWorldMapOverlay;
import com.chartplotter.route.ChartPlotterRoute;
import com.chartplotter.route.ChartPlotterRoutes;
import com.chartplotter.route.ChartPlotterSparseNodes;
import com.chartplotter.route.ChartPlotterTrip;
import com.chartplotter.util.ChartPlotterMath;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

@Singleton
public final class ChartPlotterRuntime {
	private static final int ALERT_TICKS = 8;
	private static final int CLICK_SLOP = 4;
	private static final int MAX_WORLD_TILE = 0x3fff;
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
	@Inject private ChartPlotterSparseNodes sparseNodes;
	@Inject private ChartPlotterScene scene;
	@Inject private Notifier notifier;
	@Inject private KeyManager keyManager;
	private boolean collisionActive;
	private boolean editorCacheActive;
	private boolean inputRegistered;
	private volatile boolean focused = true;
	private int alertX = Integer.MIN_VALUE;
	private int alertY = Integer.MIN_VALUE;
	private int downX;
	private int downY;
	private boolean down;
	private boolean dragged;
	private boolean downCtrl;
	private boolean downShift;
	private boolean downAlt;
	private boolean downBlock;
	private boolean menuBlock;
	private int draggedStop = -1;
	private int draggedX;
	private int draggedY;
	private boolean stopPress;
	private volatile ChartPlotterFeatures features = ChartPlotterFeatures.off();
	private final MouseAdapter mouse = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseMods(e.isControlDown(), e.isShiftDown());
			if (e.getButton() != MouseEvent.BUTTON1) return e;
			cancelStopDrag();
			Point m = new Point(e.getX(), e.getY());
			down = true;
			dragged = false;
			downX = e.getX();
			downY = e.getY();
			downCtrl = e.isControlDown();
			downShift = e.isShiftDown();
			downAlt = e.isAltDown();
			downBlock = menuBlock || client.isMenuOpen() || features.edit && worldMapOverlay.movingNode() || e.isAltDown() || worldMapOverlay.cachedClickBlocked();
			boolean mod = e.isAltDown() || e.isControlDown() || e.isShiftDown();
			if (!downBlock && !mod && features.chart && sailing.boarded()) {
				int[] stop = worldMapOverlay.cachedStop(m);
				if (stop != null) {
					draggedStop = stop[0];
					draggedX = stop[1];
					draggedY = stop[2];
					stopPress = true;
					worldMapOverlay.dragStop(draggedStop, draggedX, draggedY, m);
					e.consume();
				}
			}
			if (features.edit && worldMapOverlay.movingNode()) clientThread.invoke(() -> {
				if (!worldMapOverlay.clickBlocked()) worldMapOverlay.placeNode(m);
			});
			else if (e.isAltDown() && features.edit) clientThread.invoke(() -> {
				if (!worldMapOverlay.clickBlocked()) worldMapOverlay.editNode(m);
			});
			if (!mod && features.course && !worldMapOverlay.movingNode() && minimapOverlay.overMinimap(m)) clientThread.invoke(() -> sailing.setCourse(m));
			return e;
		}
		@Override
		public MouseEvent mouseReleased(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseMods(e.isControlDown(), e.isShiftDown());
			boolean moved = dragged || Math.abs(e.getX() - downX) > CLICK_SLOP || Math.abs(e.getY() - downY) > CLICK_SLOP;
			int stop = draggedStop;
			int oldX = draggedX;
			int oldY = draggedY;
			if (e.getButton() == MouseEvent.BUTTON1 && down && stop >= 0) {
				if (moved) {
					Point drop = new Point(e.getX(), e.getY());
					if (!downBlock) clientThread.invoke(() -> {
						int[] dst = worldMapOverlay.tile(drop);
						if (dst != null) routes.move(stop, oldX, oldY, dst[0], dst[1]);
					});
				} else if (!downBlock) clientThread.invoke(() -> routes.truncate(stop, oldX, oldY));
			} else if (e.getButton() == MouseEvent.BUTTON1 && down && !moved && !downBlock && features.chart && !downAlt) {
				Point m = new Point(e.getX(), e.getY());
				boolean active = courseClick();
				boolean append = active && downShift;
				clientThread.invokeLater(() -> clientThread.invokeLater(() -> chartCourse(m, active, append)));
			}
			if (e.getButton() == MouseEvent.BUTTON1) {
				if (stop >= 0) e.consume();
				draggedStop = -1;
				worldMapOverlay.clearStopDrag();
				down = false;
				menuBlock = false;
			}
			return e;
		}
		@Override
		public MouseEvent mouseClicked(MouseEvent e) {
			if (e.getButton() == MouseEvent.BUTTON1 && stopPress) {
				stopPress = false;
				e.consume();
			}
			return e;
		}
		@Override
		public MouseEvent mouseMoved(MouseEvent e) {
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseMods(e.isControlDown(), e.isShiftDown());
			return e;
		}
		@Override
		public MouseEvent mouseDragged(MouseEvent e) {
			if (down && (Math.abs(e.getX() - downX) > CLICK_SLOP || Math.abs(e.getY() - downY) > CLICK_SLOP)) dragged = true;
			if (draggedStop >= 0) {
				worldMapOverlay.dragStop(draggedStop, draggedX, draggedY, new Point(e.getX(), e.getY()));
				e.consume();
			}
			worldMapOverlay.nodeAlt(e.isAltDown());
			worldMapOverlay.courseMods(e.isControlDown(), e.isShiftDown());
			return e;
		}
		@Override
		public MouseEvent mouseExited(MouseEvent e) {
			down = false;
			cancelStopDrag();
			worldMapOverlay.nodeAlt(false);
			worldMapOverlay.courseMods(false, false);
			return e;
		}
	};
	private final KeyListener key = new KeyListener() {
		@Override
		public void keyTyped(KeyEvent e) {}
		@Override
		public void keyPressed(KeyEvent e) {mods(e);}
		@Override
		public void keyReleased(KeyEvent e) {mods(e);}
		@Override
		public void focusLost() {clearMods();}
	};
	public void start() {apply();}
	public void stop() {
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(worldMapOverlay);
		if (inputRegistered) {
			mouseManager.unregisterMouseListener(mouse);
			keyManager.unregisterKeyListener(key);
			inputRegistered = false;
		}
		clearMods();
		collisionActive = false;
		editorCacheActive = false;
		features = ChartPlotterFeatures.off();
		collisionCache.stop();
		routes.stop();
		sparseNodes.stop();
		sailing.reset();
	}
	public void config(ConfigChanged e) {if ("chartplotter".equals(e.getGroup())) apply();}
	public void varbit(VarbitChanged e) {
		if (!features.tracking) return;
		sailing.varbit(e);
		if (sailing.boarded()) return;
		routes.pause();
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
		if (features.worldOverlay || features.minimapOverlay || features.worldMapOverlay) scene.update(wv);
		capture(wv);
	}
	@SuppressWarnings({"unused", "UnusedParameters"})
	public void menu(MenuOpened e) {
		Point m = client.getMouseCanvasPosition();
		if (features.edit) {
			int[] node = worldMapOverlay.node(m);
			if (node != null) {
				menuBlock = true;
				client.getMenu().createMenuEntry(-1).setOption("Remove node").setTarget("Chart Plotter").setType(MenuAction.RUNELITE).onClick(me -> worldMapOverlay.removeNode(node[0], node[1]));
			}
		}
		if (!features.chart) return;
		int stop = worldMapOverlay.stop(m);
		int[] dst = sailing.boarded() ? worldMapOverlay.tile(m) : null;
		if (stop < 0 && dst == null) return;
		menuBlock = true;
		ChartPlotterTrip trip = routes.trip();
		if (!trip.empty()) client.getMenu().createMenuEntry(-1).setOption("Clear trip").setTarget("Chart Plotter").setType(MenuAction.RUNELITE).onClick(me -> routes.clear());
		if (stop >= 0) {
			int x = trip.x(stop);
			int y = trip.y(stop);
			client.getMenu().createMenuEntry(-1).setOption("Remove stop and later").setTarget("Chart Plotter").setType(MenuAction.RUNELITE).onClick(me -> routes.truncate(stop, x, y));
			return;
		}
		if (!sailing.boarded()) return;
		if (!trip.empty() && routes.canAppend()) client.getMenu().createMenuEntry(-1).setOption("Add stop").setTarget("Chart Plotter").setType(MenuAction.RUNELITE).onClick(me -> routes.append(dst[0], dst[1]));
		client.getMenu().createMenuEntry(-1).setOption("Set destination").setTarget("Chart Plotter").setType(MenuAction.RUNELITE).onClick(me -> routes.set(dst[0], dst[1]));
	}
	public void menu(MenuOptionClicked e) {
		if (!features.course) return;
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
			routes.pause();
			collision(false, null);
			return;
		}
		LocalPoint loc = sailing.anchorLoc(ship);
		if (loc == null) {
			collision(false, null);
			return;
		}
		WorldView top = sailing.top();
		boolean sceneChanged = top != null && sailing.sceneChanged(top);
		if (top != null && (features.worldOverlay || features.minimapOverlay || features.worldMapOverlay)) scene.update(top);
		if (sceneChanged) sailing.scene(ship, loc);
		boolean normal = features.cache(sailing.boarded()) && top != null;
		boolean started = collision(normal, top);
		if (sceneChanged && normal && !started) capture(top);
		if (features.chart && top != null) routes.tick(top, ship, loc);
		sailing.motion(ship, loc, sceneChanged);
		alert(top, loc);
	}
	public void focus(boolean focused) {this.focused = focused;}
	public void message(PluginMessage e) {
		if (!"chartplotter".equals(e.getNamespace())) return;
		if ("clear".equals(e.getName())) {
			clientThread.invoke(routes::clear);
			return;
		}
		if (!"chart".equals(e.getName())) return;
		int x = tile(e.getData().get("x"));
		int y = tile(e.getData().get("y"));
		if (x < 0 || y < 0) return;
		clientThread.invoke(() -> {
			if (features.chart && sailing.boarded()) routes.set(x, y);
		});
	}
	private void alert(WorldView top, LocalPoint loc) {
		ChartPlotterRoute r = routes.route();
		if (!config.courseTurnAlert() || top == null || r == null || sailing.reversing()) {
			alertX = Integer.MIN_VALUE;
			alertY = Integer.MIN_VALUE;
			return;
		}
		int bx = ChartPlotterMath.worldTile(top.getBaseX(), loc.getX());
		int by = ChartPlotterMath.worldTile(top.getBaseY(), loc.getY());
		ChartPlotterRoutes.Turn turn = ChartPlotterRoutes.turn(r, bx, by, sailing.speed(), sailing.accel(), sailing.maxSpeed());
		if (!turn.valid || turn.end || turn.ticks < 0 || turn.ticks > ALERT_TICKS || turn.x == alertX && turn.y == alertY || focused) return;
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
		if (!next.chart) {
			routes.stop();
			cancelStopDrag();
		}
		if (next.chart || next.edit) sparseNodes.start();
		else sparseNodes.stop();
		if (next.input && !inputRegistered) {
			mouseManager.registerMouseListener(mouse);
			keyManager.registerKeyListener(key);
			inputRegistered = true;
		} else if (!next.input && inputRegistered) {
			mouseManager.unregisterMouseListener(mouse);
			keyManager.unregisterKeyListener(key);
			inputRegistered = false;
			clearMods();
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
	private void chartCourse(Point m, boolean active, boolean append) {
		if (!features.chart || worldMapOverlay.clickBlocked()) return;
		int stop = worldMapOverlay.stop(m);
		if (stop >= 0) {
			if (!append) routes.truncate(stop);
			return;
		}
		if (!active || !sailing.boarded()) return;
		int[] dst = worldMapOverlay.tile(m);
		if (dst == null) return;
		if (append) routes.append(dst[0], dst[1]);
		else routes.set(dst[0], dst[1]);
	}
	private boolean courseClick() {
		if (!features.chart || !sailing.boarded() || downAlt) return false;
		ChartPlotterWorldMapClick click = config.worldMapCourseClick();
		return click == ChartPlotterWorldMapClick.CLICK || click == ChartPlotterWorldMapClick.CTRL_CLICK && downCtrl;
	}
	private void mods(KeyEvent e) {
		boolean alt = e.isAltDown();
		boolean ctrl = e.isControlDown();
		boolean shift = e.isShiftDown();
		if (e.getID() == KeyEvent.KEY_PRESSED) {
			if (e.getKeyCode() == KeyEvent.VK_ALT || e.getKeyCode() == KeyEvent.VK_ALT_GRAPH) alt = true;
			if (e.getKeyCode() == KeyEvent.VK_CONTROL) ctrl = true;
			if (e.getKeyCode() == KeyEvent.VK_SHIFT) shift = true;
		} else if (e.getID() == KeyEvent.KEY_RELEASED) {
			if (e.getKeyCode() == KeyEvent.VK_ALT || e.getKeyCode() == KeyEvent.VK_ALT_GRAPH) alt = false;
			if (e.getKeyCode() == KeyEvent.VK_CONTROL) ctrl = false;
			if (e.getKeyCode() == KeyEvent.VK_SHIFT) shift = false;
		}
		worldMapOverlay.nodeAlt(alt);
		worldMapOverlay.courseMods(ctrl, shift);
	}
	private void clearMods() {
		cancelStopDrag();
		worldMapOverlay.nodeAlt(false);
		worldMapOverlay.courseMods(false, false);
	}
	private void cancelStopDrag() {
		draggedStop = -1;
		stopPress = false;
		worldMapOverlay.clearStopDrag();
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
	private static int tile(Object value) {
		if (!(value instanceof Integer)) return -1;
		int tile = (Integer) value;
		return tile >= 0 && tile <= MAX_WORLD_TILE ? tile : -1;
	}
}
