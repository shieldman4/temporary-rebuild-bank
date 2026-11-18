package com.rebuildbank;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Units;

@ConfigGroup(RebuildBankConfig.GROUP)
public interface RebuildBankConfig extends Config
{
	String GROUP = "rebuildbank";

	@ConfigSection(name = "Untradeables", description = "Untradeables already in your bank when you start", position = 10)
	String untradeableSection = "untradeables";

	@ConfigSection(name = "Timer overlay", description = "In-game timer", position = 20)
	String overlaySection = "overlay";

	@ConfigItem(
		keyName = "showRebuildView",
		name = "Show temporary bank",
		description = "Show your temporary bank instead of your real bank. Can also be toggled from the button in the bank.",
		position = 1
	)
	default boolean showRebuildView()
	{
		return true;
	}

	@ConfigItem(
		keyName = "buyTradeables",
		name = "Buy from hidden bank",
		description = "Lets you withdraw tradeables from your real (hidden) bank by paying for them.<br>"
			+ "Each one costs its Grand Exchange price, paid only with coins in your temporary bank - "
			+ "the coins in your real bank are never used "
			+ "(anything your coins can't cover shows as debt).<br>"
			+ "Off: you can only withdraw what's in your temporary bank.",
		position = 2
	)
	default boolean buyTradeables()
	{
		return false;
	}

	@ConfigItem(
		keyName = "valueUntradeables",
		name = "Value untradeables",
		description = "Count untradeable items in your temporary bank at their high alch value (otherwise 0 gp)",
		position = 3
	)
	default boolean valueUntradeables()
	{
		return false;
	}

	@ConfigItem(
		keyName = "carriedAreBorrowed",
		name = "Don't count starting items",
		description = "On: tradeables you're wearing or carrying when you press Start don't count towards your "
			+ "temporary bank. Depositing them puts them back in your hidden real bank.<br>"
			+ "Off: everything you deposit counts, including what you started with.<br>"
			+ "Untradeables you start with always count as yours.",
		position = 4
	)
	default boolean carriedAreBorrowed()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatMessages",
		name = "Chat messages",
		description = "Show chat messages for deposits, withdrawals and rule breaks",
		position = 5
	)
	default boolean chatMessages()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyGoal",
		name = "Notify on goal",
		description = "Send a notification when the goal is reached",
		position = 6
	)
	default boolean notifyGoal()
	{
		return true;
	}

	// ---- untradeables ----

	@ConfigItem(
		keyName = "untradeableMode",
		name = "Unlock cost",
		description = "Untradeables already in your bank when you start:<br>"
			+ "Locked: they can't be unlocked at all.<br>"
			+ "Free: click to unlock them for free.<br>"
			+ "Perdu price: pay once per item what Perdu charges to repair/replace it.<br>"
			+ "Custom price: pay once per item the amount you set below.<br>"
			+ "Paid only with coins in your temporary bank - the coins in your real bank are never used "
			+ "and stay where they are.<br>"
			+ "Untradeables you earn or bring yourself are always yours.",
		position = 11,
		section = untradeableSection
	)
	default UntradeableMode untradeableMode()
	{
		return UntradeableMode.NOT_ALLOWED;
	}

	@ConfigItem(
		keyName = "untradeableFilter",
		name = "Unlockable items",
		description = "Which existing untradeables can be unlocked:<br>"
			+ "Perdu items: things Perdu repairs or sells back (fire/infernal cape, defenders, void, god capes...).<br>"
			+ "Combat gear: any untradeable gear with combat stats (adds skillcapes, cosmetic defenders...).<br>"
			+ "Everything: every untradeable, including quest items and clue junk (not recommended).<br>"
			+ "Charged, degraded or combined versions of tradeable gear (tentacle, bowfa, crystal armour, degraded "
			+ "barrows, charged chainmace/Tumeken's shadow...) are never untradeables - they count as normal items.",
		position = 14,
		section = untradeableSection
	)
	default UntradeableFilter untradeableFilter()
	{
		return UntradeableFilter.COMBAT;
	}

	@ConfigItem(
		keyName = "untradeableAlwaysAllow",
		name = "Always allow",
		description = "Untradeables to offer even if they aren't combat gear, comma separated. "
			+ "A trailing * matches any ending, e.g. Graceful*",
		position = 15,
		section = untradeableSection
	)
	default String untradeableAlwaysAllow()
	{
		return "";
	}

	@ConfigItem(
		keyName = "untradeableNeverAllow",
		name = "Never allow",
		description = "Untradeables to never offer, comma separated. A trailing * matches any ending.",
		position = 16,
		section = untradeableSection
	)
	default String untradeableNeverAllow()
	{
		return "";
	}

	@ConfigItem(
		keyName = "customUntradeableCosts",
		name = "Custom costs",
		description = "One per line, e.g.<br>Fire cape: 1m<br>Infernal cape: 5m<br>Used when Unlock cost is 'Custom price'.",
		position = 12,
		section = untradeableSection
	)
	default String customUntradeableCosts()
	{
		return "Fire cape: 1m\nInfernal cape: 5m";
	}

	@ConfigItem(
		keyName = "defaultUntradeableCost",
		name = "Cost for other items",
		description = "Unlock cost for items not in your custom list (Custom price) or that Perdu doesn't handle "
			+ "(Perdu price). Accepts 1m, 500k etc.",
		position = 13,
		section = untradeableSection
	)
	default String defaultUntradeableCost()
	{
		return "1m";
	}

	// ---- overlay ----

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show in-game timer",
		description = "Show the timer and stats as an overlay in the game",
		position = 21,
		section = overlaySection
	)
	default boolean showOverlay()
	{
		return true;
	}

	@Units(Units.MINUTES)
	@ConfigItem(
		keyName = "timeLimitMinutes",
		name = "Time limit",
		description = "Counts down from this; when it hits zero the timer flashes. 0 = no limit. Set it from the side panel.",
		hidden = true,
		position = 22,
		section = overlaySection
	)
	default int timeLimitMinutes()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "overlayShowStats",
		name = "Show value, GP/hr and PKs",
		description = "Also show net value, GP/hr, PK stats and goal progress in the overlay",
		position = 23,
		section = overlaySection
	)
	default boolean overlayShowStats()
	{
		return true;
	}
}
