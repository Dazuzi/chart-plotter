package com.chartplotter;
import com.google.inject.Provides;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
@PluginDescriptor(
	name = "Chart Plotter",
	description = "A Chart Plotter helper too to assist you with all your sailing chart plotting needs.",
	configName = "chartplotter",
	tags = {"sailing","sail","heading","navigation","chart","plotter"}
)
public class ChartPlotterPlugin extends Plugin {
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private OverlayManager overlayManager;
	@Inject private ChartPlotterOverlay overlay;
	@Inject private ChartPlotterMinimapOverlay minimapOverlay;
	@Inject private MouseManager mouseManager;
	private boolean boarded;
	private double baseSpeed;
	private double accel;
	private double moveMode;
	private double lastMoveMode = 2;
	private double speed;
	private double lastSpeed;
	private int turnDir;
	private int lastAngle;
	private int course = -1;
	private LocalPoint lastLoc;
	private final MouseAdapter mouse = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			Point m = new Point(e.getX(), e.getY());
			if (e.getButton() == MouseEvent.BUTTON1 && minimapOverlay.overMinimap(m)) clientThread.invoke(() -> setCourse(m));
			return e;
		}
	};
	@Override
	protected void startUp() {
		overlayManager.add(overlay);
		overlayManager.add(minimapOverlay);
		mouseManager.registerMouseListener(mouse);
		clientThread.invoke(this::sync);
	}
	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		mouseManager.unregisterMouseListener(mouse);
		reset();
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onVarbitChanged(VarbitChanged e) {
		int id = e.getVarbitId();
		if (id == VarbitID.SAILING_BOARDED_BOAT) boarded = e.getValue() == 1;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) baseSpeed = e.getValue() / 128.0;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) accel = e.getValue() / 128.0;
		else if (id == VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE) {
			lastMoveMode = moveMode;
			moveMode = e.getValue();
		}
		if (!boarded) {
			course = -1;
			resetMotion();
		}
	}
	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e) {
		if (e.getMenuAction() != MenuAction.SET_HEADING && !minimapOverlay.overMinimap(client.getMouseCanvasPosition())) return;
		setCourse(client.getMouseCanvasPosition());
	}
	private void setCourse(Point m) {
		WorldEntity ship = getShip();
		if (ship == null) return;
		WorldView top = client.getTopLevelWorldView();
		if (top == null || top.getYellowClickAction() != Constants.CLICK_ACTION_SET_HEADING) return;
		LocalPoint loc = ship.getLocalLocation();
		if (loc == null) return;
		int h = ChartPlotterOverlay.mouseHeading(client, top, loc, m);
		if (h >= 0) course = h;
	}
	@SuppressWarnings({"unused", "UnusedParameters"})
	@Subscribe
	public void onGameTick(GameTick e) {
		WorldEntity ship = getShip();
		if (ship == null) {
			resetMotion();
			return;
		}
		LocalPoint loc = ship.getTargetLocation();
		if (loc == null) loc = ship.getLocalLocation();
		if (loc == null) return;
		if (lastLoc != null) {
			int vx = loc.getX() - lastLoc.getX();
			int vy = loc.getY() - lastLoc.getY();
			double s = speed(vx, vy);
			double a = s - lastSpeed;
			turnDir = angleDir(lastAngle, ship.getTargetOrientation(), 0);
			lastAngle = ship.getTargetOrientation();
			if (a < 10) {
				speed = s;
				lastSpeed = speed;
			}
		} else lastAngle = ship.getTargetOrientation();
		lastLoc = loc;
	}
	@SuppressWarnings("unused")
	@Provides
	ChartPlotterConfig provideConfig(ConfigManager cm) {return cm.getConfig(ChartPlotterConfig.class);}
	WorldEntity getShip() {return getPlayerShip(client.getLocalPlayer(), client.getTopLevelWorldView());}
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isSailing() {
		WorldView top = client.getTopLevelWorldView();
		return boarded && top != null && top.getYellowClickAction() == Constants.CLICK_ACTION_SET_HEADING && getShip() != null;
	}
	int course(WorldEntity ship) {return course >= 0 ? course : norm(ship.getTargetOrientation());}
	double speed() {return speed;}
	double accel() {return accel;}
	int turnDir() {return turnDir;}
	boolean reversing() {return moveMode == 3;}
	double maxSpeed() {
		if (reversing()) return 0.5;
		double cap = 1.0;
		if (moveMode == 2 || moveMode == 4 || moveMode == 0 && lastMoveMode == 4) cap = baseSpeed;
		return cap;
	}
	static WorldEntity getPlayerShip(Player player, WorldView topWorldView) {
		if (player == null || topWorldView == null) return null;
		WorldView pv = player.getWorldView();
		if (pv == null) return null;
		int pid = pv.getId();
		for (WorldEntity we : topWorldView.worldEntities()) {
			WorldView wv = we.getWorldView();
			if (wv != null && wv.getId() == pid) return we;
		}
		return null;
	}
	static double orientationToDegrees(int angle) {return 270 - angle / (2048 / 360.0);}
	static int angleDir(int start, int target, int current) {
		double d = orientationToDegrees(target) - orientationToDegrees(start);
		if (d < -180) return -1;
		if (d > 180) return 1;
		if (d == 180 || d == -180) return current < 0 ? -1 : 1;
		if (d == 0) return 0;
		return -(int) (d / Math.abs(d));
	}
	static int norm(int v) {return ((v % 2048) + 2048) % 2048;}
	static int round(double v) {return (int) (Math.round(Math.abs(v)) * Math.signum(v));}
	static int snap(int v) {return round(v / 32.0) * 32;}
	static double speed(int vx, int vy) {return Math.round(Math.sqrt(Math.pow(vx / 128.0, 2) + Math.pow(vy / 128.0, 2)) / 0.5) * 0.5;}
	private void sync() {
		if (client.getGameState() != GameState.LOGGED_IN) return;
		boarded = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT) == 1;
		baseSpeed = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED) / 128.0;
		accel = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION) / 128.0;
		moveMode = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE);
	}
	private void reset() {
		boarded = false;
		course = -1;
		baseSpeed = 0;
		accel = 0;
		moveMode = 0;
		lastMoveMode = 2;
		resetMotion();
	}
	private void resetMotion() {
		speed = 0;
		lastSpeed = 0;
		turnDir = 0;
		lastLoc = null;
	}
}
