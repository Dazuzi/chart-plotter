package com.chartplotter;
import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.util.ColorUtil;
@ConfigGroup("chartplotter")
public interface ChartPlotterConfig extends Config {
	@ConfigItem(keyName = "stopAtCollision", name = "Collision clipping", description = "Clip projected paths at collision flags.", position = 0)
	default boolean stopAtCollision() {return true;}
	@ConfigSection(name = "Colors", description = "Shared overlay colors.", position = 0)
	String colorsSection = "colorsSection";
	@Alpha
	@ConfigItem(keyName = "lineColor", name = "Course color", description = "Color of the set-course lines.", section = colorsSection, position = 0)
	default Color lineColor() {return ColorUtil.colorWithAlpha(new Color(140, 220, 255), 185);}
	@Alpha
	@ConfigItem(keyName = "potentialColor", name = "Projected color", description = "Color of the projected course lines.", section = colorsSection, position = 1)
	default Color potentialColor() {return ColorUtil.colorWithAlpha(new Color(80, 255, 120), 185);}
	@Alpha
	@ConfigItem(keyName = "chartColor", name = "Chart color", description = "Color of charted routes.", section = colorsSection, position = 2)
	default Color chartColor() {return ColorUtil.colorWithAlpha(new Color(255, 101, 255), 185);}
	@Alpha
	@ConfigItem(keyName = "blockedColor", name = "Blocked color", description = "Color for the section of the projected path that would collide.", section = colorsSection, position = 3)
	default Color blockedColor() {return ColorUtil.colorWithAlpha(new Color(255, 80, 60), 140);}
	@ConfigSection(name = "World", description = "World overlay settings.", position = 1)
	String worldSection = "worldSection";
	@ConfigItem(keyName = "worldEnabled", name = "Enabled", description = "Draw course lines in the world.", section = worldSection, position = 0)
	default boolean worldEnabled() {return true;}
	@ConfigItem(keyName = "worldShowBlockedExtension", name = "Show blocked extension", description = "Continue drawing world projected paths past collisions.", section = worldSection, position = 1)
	default boolean worldShowBlockedExtension() {return true;}
	@ConfigItem(keyName = "lineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldSection, position = 2)
	@Range(min = 1, max = 10)
	default int worldLineWidth() {return 1;}
	@ConfigSection(name = "Minimap", description = "Minimap overlay settings.", position = 2)
	String minimapSection = "minimapSection";
	@ConfigItem(keyName = "minimapEnabled", name = "Enabled", description = "Draw course lines on the minimap.", section = minimapSection, position = 0)
	default boolean minimapEnabled() {return false;}
	@ConfigItem(keyName = "minimapShowBlockedExtension", name = "Show blocked extension", description = "Continue drawing minimap projected paths past collisions.", section = minimapSection, position = 1)
	default boolean minimapShowBlockedExtension() {return false;}
	@ConfigItem(keyName = "minimapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = minimapSection, position = 2)
	@Range(min = 1, max = 10)
	default int minimapLineWidth() {return 1;}
	@ConfigSection(name = "World Map", description = "World map overlay settings.", position = 3)
	String worldMapSection = "worldMapSection";
	@ConfigItem(keyName = "worldMapEnabled", name = "Enabled", description = "Draw course lines on the world map.", section = worldMapSection, position = 0)
	default boolean worldMapEnabled() {return true;}
	@ConfigItem(keyName = "worldMapShowBlockedExtension", name = "Show blocked extension", description = "Continue drawing world map projected paths past collisions.", section = worldMapSection, position = 1)
	default boolean worldMapShowBlockedExtension() {return false;}
	@ConfigItem(keyName = "worldMapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldMapSection, position = 2)
	@Range(min = 1, max = 10)
	default int worldMapLineWidth() {return 1;}
	@ConfigSection(name = "Charting", description = "Charted route settings.", position = 4)
	String chartingSection = "chartingSection";
	@ConfigItem(keyName = "routeShape", name = "Route shape", description = "Controls how strongly charting prefers long straight legs over the shortest route.", section = chartingSection, position = 0)
	default ChartPlotterTurnPreference routeShape() {return ChartPlotterTurnPreference.BALANCED;}
	@ConfigItem(keyName = "routeEffort", name = "Pathing effort", description = "Higher effort spends more time refining routes; lower effort returns quicker.", section = chartingSection, position = 1)
	default ChartPlotterRouteEffort routeEffort() {return ChartPlotterRouteEffort.HIGH;}
	@ConfigSection(name = "Tweaks", description = "Experimental settings.", position = 5)
	String tweaksSection = "tweaksSection";
	@ConfigItem(keyName = "cacheOverlayMode", name = "Cache overlay", description = "Draw remembered collision coverage.", section = tweaksSection, position = 3)
	default ChartPlotterCacheOverlay cacheOverlay() {return ChartPlotterCacheOverlay.OFF;}
	@ConfigItem(keyName = "nodeEditor", name = "Node editor", description = "Draw and place sparse pathing nodes on the world map.", section = tweaksSection, position = 5)
	default boolean nodeEditor() {return false;}
}
