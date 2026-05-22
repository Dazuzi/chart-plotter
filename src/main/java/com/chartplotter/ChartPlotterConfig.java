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
	@ConfigSection(name = "World", description = "World overlay settings.", position = 0)
	String worldSection = "worldSection";
	@ConfigItem(keyName = "worldEnabled", name = "Enabled", description = "Draw course lines in the world.", section = worldSection, position = 0)
	default boolean worldEnabled() {return true;}
	@Alpha
	@ConfigItem(keyName = "lineColor", name = "Course color", description = "Color of the set-course lines.", section = worldSection, position = 1)
	default Color worldLineColor() {return ColorUtil.colorWithAlpha(new Color(140, 220, 255), 90);}
	@Alpha
	@ConfigItem(keyName = "potentialColor", name = "Projected color", description = "Color of the projected course lines.", section = worldSection, position = 2)
	default Color worldPotentialColor() {return ColorUtil.colorWithAlpha(new Color(255, 170, 40), 185);}
	@ConfigItem(keyName = "lineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldSection, position = 3)
	@Range(min = 1, max = 10)
	default int worldLineWidth() {return 2;}
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
	@ConfigItem(keyName = "minimapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = minimapSection, position = 3)
	@Range(min = 1, max = 10)
	default int minimapLineWidth() {return 2;}
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
	@ConfigItem(keyName = "worldMapLineWidth", name = "Line width", description = "Stroke width in pixels.", section = worldMapSection, position = 3)
	@Range(min = 1, max = 10)
	default int worldMapLineWidth() {return 2;}
}
