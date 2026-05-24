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
	@ConfigItem(keyName = "showBlockedExtension", name = "Show blocked extension", description = "Continue drawing the projected path past collisions in a different color.", position = 1)
	default boolean showBlockedExtension() {return false;}
	@Alpha
	@ConfigItem(keyName = "blockedColor", name = "Blocked color", description = "Color for the section of the projected path that would collide.", position = 2)
	default Color blockedColor() {return ColorUtil.colorWithAlpha(new Color(255, 80, 60), 140);}
	@ConfigSection(name = "World", description = "World overlay settings.", position = 0)
	String worldSection = "worldSection";
	@ConfigItem(keyName = "worldEnabled", name = "Enabled", description = "Draw course lines in the world.", section = worldSection, position = 0)
	default boolean worldEnabled() {return true;}
	@Alpha
	@ConfigItem(keyName = "lineColor", name = "Course color", description = "Color of the set-course lines.", section = worldSection, position = 1)
	default Color worldLineColor() {return ColorUtil.colorWithAlpha(new Color(140, 220, 255), 185);}
	@Alpha
	@ConfigItem(keyName = "potentialColor", name = "Projected color", description = "Color of the projected course lines.", section = worldSection, position = 2)
	default Color worldPotentialColor() {return ColorUtil.colorWithAlpha(new Color(255, 170, 40), 185);}
	@Alpha
	@ConfigItem(keyName = "worldChartColor", name = "Chart color", description = "Color of charted world routes.", section = worldSection, position = 3)
	default Color worldChartColor() {return ColorUtil.colorWithAlpha(new Color(80, 255, 120), 185);}
	@ConfigItem(keyName = "lineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldSection, position = 4)
	@Range(min = 1, max = 10)
	default int worldLineWidth() {return 1;}
	@ConfigSection(name = "Minimap", description = "Minimap overlay settings.", position = 1)
	String minimapSection = "minimapSection";
	@ConfigItem(keyName = "minimapEnabled", name = "Enabled", description = "Draw course lines on the minimap.", section = minimapSection, position = 0)
	default boolean minimapEnabled() {return true;}
	@Alpha
	@ConfigItem(keyName = "minimapLineColor", name = "Course color", description = "Color of the set-course minimap line.", section = minimapSection, position = 1)
	default Color minimapLineColor() {return ColorUtil.colorWithAlpha(new Color(140, 220, 255), 185);}
	@Alpha
	@ConfigItem(keyName = "minimapPotentialColor", name = "Projected color", description = "Color of the projected minimap line.", section = minimapSection, position = 2)
	default Color minimapPotentialColor() {return ColorUtil.colorWithAlpha(new Color(255, 170, 40), 185);}
	@Alpha
	@ConfigItem(keyName = "minimapChartColor", name = "Chart color", description = "Color of charted minimap routes.", section = minimapSection, position = 3)
	default Color minimapChartColor() {return ColorUtil.colorWithAlpha(new Color(80, 255, 120), 185);}
	@ConfigItem(keyName = "minimapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = minimapSection, position = 4)
	@Range(min = 1, max = 10)
	default int minimapLineWidth() {return 1;}
	@ConfigSection(name = "World Map", description = "World map overlay settings.", position = 2)
	String worldMapSection = "worldMapSection";
	@ConfigItem(keyName = "worldMapEnabled", name = "Enabled", description = "Draw course lines on the world map.", section = worldMapSection, position = 0)
	default boolean worldMapEnabled() {return true;}
	@Alpha
	@ConfigItem(keyName = "worldMapLineColor", name = "Course color", description = "Color of the set-course world map line.", section = worldMapSection, position = 1)
	default Color worldMapLineColor() {return ColorUtil.colorWithAlpha(new Color(140, 220, 255), 185);}
	@Alpha
	@ConfigItem(keyName = "worldMapPotentialColor", name = "Projected color", description = "Color of the projected world map line.", section = worldMapSection, position = 2)
	default Color worldMapPotentialColor() {return ColorUtil.colorWithAlpha(new Color(255, 170, 40), 185);}
	@Alpha
	@ConfigItem(keyName = "worldMapChartColor", name = "Chart color", description = "Color of charted world map routes.", section = worldMapSection, position = 3)
	default Color worldMapChartColor() {return ColorUtil.colorWithAlpha(new Color(80, 255, 120), 210);}
	@ConfigItem(keyName = "worldMapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldMapSection, position = 4)
	@Range(min = 1, max = 10)
	default int worldMapLineWidth() {return 1;}
	@ConfigSection(name = "Charting", description = "Charted route settings.", position = 3)
	String chartingSection = "chartingSection";
	@ConfigItem(keyName = "routeShape", name = "Route shape", description = "Controls how strongly charting prefers long straight legs over the shortest route.", section = chartingSection, position = 0)
	default ChartPlotterTurnPreference routeShape() {return ChartPlotterTurnPreference.BALANCED;}
	@ConfigItem(keyName = "routeEffort", name = "Pathing effort", description = "Higher effort spends more time refining precise, footprint-aware routes; lower effort returns quicker.", section = chartingSection, position = 1)
	default ChartPlotterRouteEffort routeEffort() {return ChartPlotterRouteEffort.HIGH;}
	@ConfigSection(name = "Tweaks", description = "Experimental settings.", position = 4)
	String tweaksSection = "tweaksSection";
	@ConfigItem(keyName = "cacheCollision", name = "Remember collision", description = "Save reliable collision tiles to disk.", section = tweaksSection, position = 0)
	default boolean cacheCollision() {return true;}
	@ConfigItem(keyName = "collisionDebug", name = "Collision debug", description = "Draw sampled ship collision points.", section = tweaksSection, position = 1)
	default ChartPlotterCollisionDebug collisionDebug() {return ChartPlotterCollisionDebug.OFF;}
	@ConfigItem(keyName = "cacheOverlayMode", name = "Cache overlay", description = "Draw remembered collision coverage.", section = tweaksSection, position = 3)
	default ChartPlotterCacheOverlay cacheOverlay() {return ChartPlotterCacheOverlay.OFF;}
	@ConfigItem(keyName = "routeClearRadius", name = "Destination radius", description = "World tiles from the destination before clearing a charted route.", section = tweaksSection, position = 4)
	@Range(min = 1, max = 20)
	default int routeClearRadius() {return 5;}
}
