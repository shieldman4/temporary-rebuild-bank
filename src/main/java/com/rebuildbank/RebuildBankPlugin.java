package com.rebuildbank;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.FontID;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Temporary Rebuild Bank",
	description = "Rebuild challenge: hide your bank and build a fresh one from 0 gp, with goals, in-game timer, GP/hr, PK stats and untradeable unlocks",
	tags = {"rebuild", "challenge", "fresh start", "restart", "series", "youtube", "content", "bank", "empty bank", "gp", "gp/hr", "timer", "goal", "pk", "pvp", "untradeable", "wealth"}
)
public class RebuildBankPlugin extends Plugin
{
	private static final String SESSION_KEY = "session";
	private static final int SAVE_EVERY_TICKS = 100; // 1 minute
	private static final Color CHAT_COLOR = new Color(0x00, 0x60, 0xA0);

	// Bank title-bar toggle
	private static final String TOGGLE_NAME = "<col=ff9040>Temporary Bank</col>";
	private static final int TOGGLE_COLOR = 0xff981f;
	private static final int TOGGLE_HOVER_COLOR = 0xffffff;
	private static final int TOGGLE_BG = 0x3e3529;
	private static final int TOGGLE_BG_HOVER = 0x5a4d3c;
	private static final int TOGGLE_BORDER = 0x0e0e0c;
	private static final int TOGGLE_WIDTH = 72;
	private static final int FONT_PLAIN_11 = FontID.PLAIN_11;

	// Divider between your temporary bank and the locked untradeables
	private static final String SEPARATOR_LINE = "<col=ff9040>Temporary Bank divider</col>";
	private static final String SEPARATOR_TEXT = "<col=ff9040>Temporary Bank label</col>";
	private static final int SEPARATOR_COLOR = 0x726451;
	private static final String UNLOCK_OPTION = "Unlock";
	/** Every widget this plugin creates has a name starting with this. */
	private static final String WIDGET_PREFIX = "<col=ff9040>Temporary Bank";
	private static final String OWED_PREFIX = WIDGET_PREFIX + " owed ";
	private static final int OWED_COLOR = 0xff3030;
	private static final int MIN_ROWS = 4;
	private static final int SECTION_GAP = 12;

	private static final int[] LOOT_KEYS = {
		ItemID.WILDY_LOOT_KEY0, ItemID.WILDY_LOOT_KEY1, ItemID.WILDY_LOOT_KEY2,
		ItemID.WILDY_LOOT_KEY3, ItemID.WILDY_LOOT_KEY4
	};
	private static final int[] LOOT_KEY_CONTAINERS = {
		InventoryID.DEADMAN_LOOT_INV0, InventoryID.DEADMAN_LOOT_INV1, InventoryID.DEADMAN_LOOT_INV2,
		InventoryID.DEADMAN_LOOT_INV3, InventoryID.DEADMAN_LOOT_INV4
	};

	/** Loot keys in the inventory last time we looked; -1 until the first look. */
	private int lootKeyCount = -1;
	private boolean lootChestValued;

	// Bank item grid (matches the core bank tags layout)
	private static final int ITEM_START_X = 51;
	private static final int ITEM_WIDTH = 36;
	private static final int ITEM_HEIGHT = 32;
	private static final int ITEM_X_STEP = ITEM_WIDTH + 12;
	private static final int ITEM_Y_STEP = ITEM_HEIGHT + 4;
	private static final int ITEMS_PER_ROW = 8;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RebuildBankConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Gson gson;

	private RebuildBankPanel panel;
	private NavigationButton navButton;

	/** Session for the logged in account, or null. Only touched on the client thread. */
	private RebuildSession session;
	/** RS profile the session belongs to, so it can still be saved after logout. */
	private String profileKey;
	private long ticksSinceSave;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TimerOverlay timerOverlay;

	/** Values the overlay draws every frame, refreshed whenever the panel is. */
	@Getter
	private volatile long cachedNetValue;
	@Getter
	private volatile long cachedGoalProgress;
	@Getter
	private volatile long cachedOwedValue;

	RebuildSession getSession()
	{
		return session;
	}

	@Provides
	RebuildBankConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RebuildBankConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Fill in any settings missing from the active RuneLite profile (e.g. a newly
		// created one), otherwise the config panel can't show the dropdowns.
		configManager.setDefaultConfiguration(config, false);
		panel = new RebuildBankPanel(this, config.showRebuildView(), config.timeLimitMinutes());
		navButton = NavigationButton.builder()
			.tooltip("Temporary Rebuild Bank")
			.icon(ImageUtil.loadImageResource(RebuildBankPlugin.class, "panel_icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(timerOverlay);

		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				loadSession();
			}
			refreshPanel();
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invokeLater(() ->
		{
			saveSession();
			session = null;
			profileKey = null;
			rebuildBankInterface();
			// we're no longer subscribed to the bank's build script, so hide our widgets directly
			applyBankView();
			updateToggleButton();
		});
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(timerOverlay);
		panel = null;
		navButton = null;
	}

	// ------------------------------------------------------------------ panel actions

	void startSession()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			profileKey = configManager.getRSProfileKey();
			GoalType oldGoal = session != null ? session.goalType : GoalType.NONE;
			long oldTarget = session != null ? session.goalTarget : 0;
			int oldItem = session != null ? session.goalItemId : -1;
			String oldItemName = session != null ? session.goalItemName : null;

			session = RebuildSession.start(System.currentTimeMillis());
			// Keep the previous goal so a new attempt can be started quickly
			session.goalType = oldGoal;
			session.goalTarget = oldTarget;
			session.goalItemId = oldItem;
			session.goalItemName = oldItemName;

			RebuildSession.Policy policy = policy();
			Map<Integer, Long> carried = new HashMap<>();
			addContainer(carried, InventoryID.INV);
			addContainer(carried, InventoryID.WORN);
			if (!config.carriedAreBorrowed())
			{
				// tradeables you carry count as yours; untradeables still follow the untradeable rules
				carried.keySet().removeIf(id -> !policy.isUntradeable(id));
			}
			session.borrowCarried(carried, policy);

			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			if (bank != null)
			{
				session.applyRealBank(snapshot(bank), policy());
				chat("Temporary bank started! Your bank is now empty. Timer is running.");
			}
			else
			{
				chat("Temporary bank started! Open your bank once to set the starting point - the timer starts then.");
			}
			ticksSinceSave = 0;
			saveSession();
			rebuildBankInterface();
			refreshPanel();
		});
	}

	void endSession()
	{
		clientThread.invokeLater(() ->
		{
			if (session == null)
			{
				return;
			}
			chat("Temporary bank ended after " + RebuildSession.formatTicks(session.challengeTicks())
				+ " with a net value of " + Amounts.format(session.netValue(this::unitValue)) + " gp.");
			session = null;
			if (profileKey != null)
			{
				configManager.unsetConfiguration(RebuildBankConfig.GROUP, profileKey, SESSION_KEY);
			}
			rebuildBankInterface();
			refreshPanel();
		});
	}

	void setGoal(GoalType type, String itemText, long amount)
	{
		clientThread.invokeLater(() ->
		{
			if (session == null)
			{
				return;
			}
			int itemId = -1;
			String itemName = null;
			if (type == GoalType.ITEM)
			{
				itemId = resolveItem(itemText);
				if (itemId < 0)
				{
					SwingUtilities.invokeLater(() -> panel.showMessage("Couldn't find an item called \"" + itemText
						+ "\". Use the exact name of a tradeable item, or an item ID."));
					return;
				}
				itemName = itemManager.getItemComposition(itemId).getName();
			}
			session.goalType = type;
			session.goalTarget = amount;
			session.goalItemId = itemId;
			session.goalItemName = itemName;
			// A new goal restarts the clock from where it stopped if it isn't already met
			session.completedAtTicks = -1;
			checkGoal();
			saveSession();
			refreshPanel();
		});
	}

	void setTimeLimit(int minutes)
	{
		configManager.setConfiguration(RebuildBankConfig.GROUP, "timeLimitMinutes", Math.max(0, minutes));
	}

	void setViewEnabled(boolean enabled)
	{
		configManager.setConfiguration(RebuildBankConfig.GROUP, "showRebuildView", enabled);
	}

	// ------------------------------------------------------------------ events

	@Subscribe
	public void onProfileChanged(ProfileChanged e)
	{
		// switching RuneLite settings profiles: make sure this profile has our defaults too
		configManager.setDefaultConfiguration(config, false);
		RebuildBankPanel p = panel;
		if (p != null)
		{
			boolean view = config.showRebuildView();
			int limit = config.timeLimitMinutes();
			SwingUtilities.invokeLater(() ->
			{
				p.setViewToggle(view);
				p.setTimeLimit(limit);
			});
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		saveSession();
		loadSession();
		rebuildBankInterface();
		refreshPanel();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState state = e.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			saveSession();
		}
		if (state == GameState.LOGIN_SCREEN)
		{
			session = null;
			profileKey = null;
			refreshPanel();
		}
		else if (state == GameState.LOGGED_IN && session == null)
		{
			loadSession();
			refreshPanel();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (session == null || !session.isTiming() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		session.ticks++;
		if (++ticksSinceSave >= SAVE_EVERY_TICKS)
		{
			ticksSinceSave = 0;
			saveSession();
		}
		// The timer only needs a once-a-second refresh
		if (session.ticks % 2 == 0)
		{
			refreshPanel();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (session != null && e.getContainerId() == InventoryID.INV)
		{
			trackLootKeys(e.getItemContainer());
		}
		if (session == null || e.getContainerId() != InventoryID.BANK)
		{
			return;
		}

		boolean wasPending = session.baselinePending;
		List<RebuildSession.Change> changes = session.applyRealBank(snapshot(e.getItemContainer()), policy());
		if (wasPending)
		{
			chat("Starting point recorded. Your temporary bank is empty and the timer is running.");
		}
		reportChanges(changes);
		checkGoal();
		saveSession();
		refreshPanel();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (e.getParam1() != InterfaceID.Bankmain.ITEMS)
		{
			return;
		}
		String option = e.getMenuOption() == null ? null : Text.removeTags(e.getMenuOption());
		if (UNLOCK_OPTION.equals(option))
		{
			// Our own option on a locked untradeable: it must never reach the game
			e.consume();
			if (session != null && !session.baselinePending)
			{
				unlockItem(clickedItem(e));
			}
			return;
		}
		if (session == null || session.baselinePending)
		{
			return;
		}
		// Viewing the real bank: withdraw freely. Blocking resumes in the temp bank view.
		if (!config.showRebuildView())
		{
			return;
		}
		if (option == null || !option.startsWith("Withdraw"))
		{
			return;
		}
		int itemId = clickedItem(e);
		if (itemId <= 0)
		{
			return;
		}

		RebuildSession.Policy policy = policy();
		if (session.count(itemId) <= 0 && session.unlockable(policy).containsKey(itemId))
		{
			e.consume();
			chat("Unlock " + itemManager.getItemComposition(itemId).getName() + " first (click it in your temporary bank).");
			return;
		}
		long allowed = session.withdrawable(itemId, policy);

		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		long real = bank != null ? bank.count(itemId) : 0;
		long wanted = requestedAmount(option, real);
		boolean overdraw = wanted < 0 ? allowed <= 0 : Math.min(wanted, real) > allowed;
		if (!overdraw)
		{
			return;
		}

		String name = itemManager.getItemComposition(itemId).getName();
		if (allowed <= 0)
		{
			e.consume();
			chat(name + " isn't in your temporary bank.");
			return;
		}

		// Withdraw-All / All-but-1 / a too-big number: block it, so the real stack never
		// leaves the bank, and tell the player which option takes exactly what they own.
		e.consume();
		long lastX = client.getVarbitValue(VarbitID.BANK_REQUESTEDQUANTITY);
		boolean oneClick = allowed == 1 || allowed == 5 || allowed == 10 || allowed == lastX;
		chat("Your temporary bank has " + Amounts.format(allowed) + " x " + name + " - "
			+ (oneClick
				? "use Withdraw-" + allowed + "."
				: "use Withdraw-X and type " + allowed + "."), true);
	}

	// ------------------------------------------------------------------ player kills

	/** A player you killed dropped loot on the ground. */
	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived e)
	{
		if (session == null || !session.isTiming())
		{
			return;
		}
		long value = 0;
		boolean onlyBones = true;
		for (ItemStack item : e.getItems())
		{
			int id = itemManager.canonicalize(item.getId());
			if (id != ItemID.BONES)
			{
				onlyBones = false;
			}
			value += unitValue(id) * item.getQuantity();
		}
		// With loot keys on, only bones hit the floor; the loot key counts that kill instead
		if (onlyBones)
		{
			return;
		}
		session.recordKill(value);
		saveSession();
		refreshPanel();
	}

	/** Each new loot key in your inventory is a kill (loot keys on). */
	private void trackLootKeys(ItemContainer inventory)
	{
		int keys = 0;
		for (int keyId : LOOT_KEYS)
		{
			keys += inventory.count(keyId);
		}
		if (lootKeyCount >= 0 && keys > lootKeyCount && session.isTiming())
		{
			for (int i = lootKeyCount; i < keys; i++)
			{
				session.recordKill(0);
			}
			saveSession();
			refreshPanel();
		}
		if (keys == 0)
		{
			lootChestValued = false; // keys used up: the next chest visit is new loot
		}
		lootKeyCount = keys;
	}

	/** Opening the loot chest shows what your keys were worth. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() != InterfaceID.WILDY_LOOT_CHEST || session == null || lootChestValued)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			long value = 0;
			for (int containerId : LOOT_KEY_CONTAINERS)
			{
				for (Map.Entry<Integer, Long> item : snapshot(client.getItemContainer(containerId)).entrySet())
				{
					value += unitValue(item.getKey()) * item.getValue();
				}
			}
			if (value > 0 && session != null)
			{
				lootChestValued = true;
				session.addKillLoot(value);
				saveSession();
				refreshPanel();
			}
		});
	}

	/**
	 * The item on the clicked bank slot. Bank menu entries don't always carry an item
	 * id, so read it from the clicked widget, falling back to the bank slot index.
	 */
	private int clickedItem(MenuOptionClicked e)
	{
		int id = -1;
		Widget w = e.getWidget();
		if (w != null)
		{
			id = w.getItemId();
		}
		if (id <= 0)
		{
			id = e.getItemId();
		}
		if (id <= 0)
		{
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			Item slot = bank != null ? bank.getItem(e.getParam0()) : null;
			id = slot != null ? slot.getId() : -1;
		}
		return id > 0 ? itemManager.canonicalize(id) : -1;
	}

	private void unlockItem(int itemId)
	{
		if (session == null || itemId <= 0)
		{
			return;
		}
		String name = itemManager.getItemComposition(itemId).getName();
		RebuildSession.Change c = session.unlock(itemId, policy());
		if (c == null)
		{
			chat(name + " can't be unlocked.", true);
			return;
		}
		if (c.unlocked == 0)
		{
			chat("Unlocking " + name + " costs " + Amounts.format(c.unlockCost) + " temporary bank coins - you have "
				+ Amounts.format(session.count(RebuildSession.COINS)) + ". Coins in your real bank can't be used.", true);
			return;
		}
		chat("Unlocked " + (c.unlocked > 1 ? Amounts.format(c.unlocked) + " x " : "") + name
			+ (c.unlockCost > 0
				? " for " + Amounts.format(c.unlockCost) + " temporary bank coins. Your real coins stay in your bank."
				: " for free."), true);
		checkGoal();
		saveSession();
		rebuildBankInterface();
		refreshPanel();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() == ScriptID.BANKMAIN_BUILD)
		{
			applyBankView();
			updateToggleButton();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!RebuildBankConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if ("showRebuildView".equals(e.getKey()))
		{
			if (panel != null)
			{
				SwingUtilities.invokeLater(() -> panel.setViewToggle(config.showRebuildView()));
			}
			clientThread.invokeLater(this::rebuildBankInterface);
		}
		else if ("timeLimitMinutes".equals(e.getKey()))
		{
			RebuildBankPanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(() -> p.setTimeLimit(config.timeLimitMinutes()));
			}
		}
		else if (e.getKey().startsWith("untradeable") || "valueUntradeables".equals(e.getKey())
			|| "customUntradeableCosts".equals(e.getKey()) || "defaultUntradeableCost".equals(e.getKey()))
		{
			clientThread.invokeLater(() ->
			{
				checkGoal();
				refreshPanel();
				rebuildBankInterface();
			});
		}
	}

	// ------------------------------------------------------------------ bank interface

	private void applyBankView()
	{
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null || container.isHidden())
		{
			return;
		}
		Widget sepLine = findNamed(container, SEPARATOR_LINE);
		Widget sepText = findNamed(container, SEPARATOR_TEXT);
		hideAll(container, OWED_PREFIX);
		if (session == null || session.baselinePending || !config.showRebuildView())
		{
			hide(sepLine);
			hide(sepText);
			return;
		}
		Widget[] children = container.getChildren();
		if (children == null)
		{
			return;
		}

		// Keep the bank's own order (respects the selected tab and search), but only
		// show your temporary bank, packed into a gap-free grid, then a divider and
		// the existing untradeables you can unlock.
		RebuildSession.Policy policy = policy();
		Map<Integer, Long> lockable = session.unlockable(policy);
		List<Widget> owned = new ArrayList<>();
		List<Widget> locked = new ArrayList<>();
		Map<Integer, Boolean> seen = new HashMap<>();
		for (Widget c : children)
		{
			if (c == null || c.isHidden() || isOurs(c))
			{
				continue;
			}
			int id = c.getItemId();
			boolean itemSlot = c.getOriginalWidth() == ITEM_WIDTH && c.getOriginalHeight() == ITEM_HEIGHT;
			if (!itemSlot || id <= 0 || id == ItemID.BANK_FILLER)
			{
				// tab separators, tab labels, empty slots and fillers
				c.setHidden(true);
				continue;
			}
			ItemComposition def = client.getItemDefinition(id);
			int canon = def.getPlaceholderTemplateId() >= 0 ? -1 : itemManager.canonicalize(id);
			long have = canon > 0 ? session.count(canon) : 0;
			long lockedQty = canon > 0 ? lockable.getOrDefault(canon, 0L) : 0;
			if (have + lockedQty <= 0 || seen.putIfAbsent(canon, true) != null)
			{
				c.setHidden(true);
				continue;
			}
			if (have > 0)
			{
				c.setItemQuantity((int) Math.min(Integer.MAX_VALUE, have));
				c.setOpacity(0);
				owned.add(c);
			}
			else
			{
				long cost = session.unlockFee(canon, policy);
				c.setItemQuantity((int) Math.min(Integer.MAX_VALUE, lockedQty));
				c.setOpacity(110);
				c.clearActions();
				c.setAction(0, UNLOCK_OPTION);
				c.setName("<col=ff9040>" + def.getName() + "</col> <col=ffff00>("
					+ (cost == 0 ? "free" : Amounts.shortFormat(cost) + " temp coins") + ")</col>");
				locked.add(c);
			}
		}

		Comparator<Widget> order = Comparator.comparingInt(Widget::getOriginalY).thenComparingInt(Widget::getOriginalX);
		owned.sort(order);
		locked.sort(order);
		int gridWidth = (ITEMS_PER_ROW - 1) * ITEM_X_STEP + ITEM_WIDTH;

		// Anything taken out of the real bank beyond the limit goes at the very top, in red
		int ownedTop = layoutOwed(container, gridWidth);
		int y = layoutGrid(owned, ownedTop);

		// Your temporary bank always keeps at least 4 rows of room above the
		// untradeables, empty or not, so there's space to see it fill up.
		y = Math.max(y, ownedTop + MIN_ROWS * ITEM_Y_STEP);

		if (!locked.isEmpty())
		{
			int top = y + SECTION_GAP;
			if (sepText == null)
			{
				sepText = container.createChild(-1, WidgetType.TEXT);
				sepText.setName(SEPARATOR_TEXT);
				sepText.setFontId(FONT_PLAIN_11);
				sepText.setTextShadowed(true);
				sepText.setXTextAlignment(WidgetTextAlignment.CENTER);
				sepText.setYTextAlignment(WidgetTextAlignment.CENTER);
				sepText.setTextColor(TOGGLE_COLOR);
			}
			sepText.setText(policy.untradeableMode() == UntradeableMode.ALLOWED
				? "Untradeables - click to unlock (free)"
				: "Untradeables - unlock with temporary bank coins");
			sepText.setOriginalX(ITEM_START_X);
			sepText.setOriginalY(top);
			sepText.setOriginalWidth(gridWidth);
			sepText.setOriginalHeight(14);
			sepText.setHidden(false);
			sepText.revalidate();

			if (sepLine == null)
			{
				sepLine = container.createChild(-1, WidgetType.RECTANGLE);
				sepLine.setName(SEPARATOR_LINE);
				sepLine.setFilled(true);
				sepLine.setTextColor(SEPARATOR_COLOR);
			}
			sepLine.setOriginalX(ITEM_START_X);
			sepLine.setOriginalY(top + 16);
			sepLine.setOriginalWidth(gridWidth);
			sepLine.setOriginalHeight(1);
			sepLine.setHidden(false);
			sepLine.revalidate();

			y = layoutGrid(locked, top + 22);
		}
		else
		{
			hide(sepLine);
			hide(sepText);
		}

		int height = Math.max(container.getHeight(), y + 4);
		container.setScrollHeight(height);
		int maxScroll = Math.max(0, height - container.getHeight());
		if (container.getScrollY() > maxScroll)
		{
			container.setScrollY(maxScroll);
		}
		container.revalidateScroll();
		final int scrollY = container.getScrollY();
		clientThread.invokeLater(() ->
			client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS, scrollY));

		Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (title != null)
		{
			title.setText("Temporary Bank - " + Amounts.format(session.netValue(this::unitValue)) + " gp");
		}
	}

	/**
	 * Draws the "owed to your real bank" section: a red label and each owed item
	 * with a red "-amount". These are our own picture-only widgets with no click
	 * actions. Returns the y where the temporary bank's own items start.
	 */
	private int layoutOwed(Widget container, int gridWidth)
	{
		Map<Integer, Long> owed = session.getOwed();
		if (owed.isEmpty())
		{
			return 0;
		}

		Widget label = namedChild(container, OWED_PREFIX + "label", WidgetType.TEXT);
		label.setFontId(FONT_PLAIN_11);
		label.setTextShadowed(true);
		label.setXTextAlignment(WidgetTextAlignment.CENTER);
		label.setYTextAlignment(WidgetTextAlignment.CENTER);
		label.setTextColor(OWED_COLOR);
		label.setText("You owe your real bank - deposit these back");
		place(label, ITEM_START_X, 0, gridWidth, 14);

		int top = 18;
		int i = 0;
		List<Map.Entry<Integer, Long>> entries = new ArrayList<>(owed.entrySet());
		entries.sort(Map.Entry.comparingByKey());
		for (Map.Entry<Integer, Long> e : entries)
		{
			int x = ITEM_START_X + (i % ITEMS_PER_ROW) * ITEM_X_STEP;
			int iy = top + (i / ITEMS_PER_ROW) * ITEM_Y_STEP;

			Widget icon = namedChild(container, OWED_PREFIX + "item" + i, WidgetType.GRAPHIC);
			icon.setItemId(e.getKey());
			icon.setItemQuantity(1);
			icon.setItemQuantityMode(ItemQuantityMode.NEVER);
			icon.setOpacity(0);
			place(icon, x, iy, ITEM_WIDTH, ITEM_HEIGHT);

			Widget qty = namedChild(container, OWED_PREFIX + "qty" + i, WidgetType.TEXT);
			qty.setFontId(FONT_PLAIN_11);
			qty.setTextShadowed(true);
			qty.setXTextAlignment(WidgetTextAlignment.LEFT);
			qty.setYTextAlignment(WidgetTextAlignment.TOP);
			qty.setTextColor(OWED_COLOR);
			qty.setText("-" + Amounts.shortFormat(e.getValue()));
			place(qty, x, iy - 1, ITEM_X_STEP, 12);
			i++;
		}
		int rows = (i + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		return top + rows * ITEM_Y_STEP + SECTION_GAP;
	}

	private static Widget namedChild(Widget parent, String name, int type)
	{
		Widget w = findNamed(parent, name);
		if (w == null || w.getType() != type)
		{
			w = parent.createChild(-1, type);
			w.setName(name);
		}
		return w;
	}

	private static void place(Widget w, int x, int y, int width, int height)
	{
		w.setOriginalX(x);
		w.setOriginalY(y);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
		w.setHidden(false);
		w.revalidate();
	}

	private static boolean isOurs(Widget w)
	{
		String name = w.getName();
		return name != null && name.startsWith(WIDGET_PREFIX);
	}

	private static void hideAll(Widget parent, String prefix)
	{
		Widget[] dynamic = parent.getDynamicChildren();
		if (dynamic != null)
		{
			for (Widget w : dynamic)
			{
				if (w != null && w.getName() != null && w.getName().startsWith(prefix))
				{
					w.setHidden(true);
				}
			}
		}
	}

	/** Lays widgets out in the bank grid starting at y; returns the y below the last row. */
	private static int layoutGrid(List<Widget> widgets, int y)
	{
		for (int i = 0; i < widgets.size(); i++)
		{
			Widget c = widgets.get(i);
			c.setOriginalX(ITEM_START_X + (i % ITEMS_PER_ROW) * ITEM_X_STEP);
			c.setOriginalY(y + (i / ITEMS_PER_ROW) * ITEM_Y_STEP);
			c.revalidate();
		}
		int rows = (widgets.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		return y + rows * ITEM_Y_STEP;
	}

	private static Widget findNamed(Widget parent, String name)
	{
		Widget[] dynamic = parent.getDynamicChildren();
		if (dynamic != null)
		{
			for (Widget w : dynamic)
			{
				if (w != null && name.equals(w.getName()))
				{
					return w;
				}
			}
		}
		return null;
	}

	private static void hide(Widget w)
	{
		if (w != null)
		{
			w.setHidden(true);
		}
	}

	/** Stone-style "Real bank / Temp bank" switch in the bank's title bar. */
	private void updateToggleButton()
	{
		Widget universe = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (universe == null || title == null)
		{
			return;
		}

		Widget bg = findNamed(universe, TOGGLE_NAME + "bg");
		Widget border = findNamed(universe, TOGGLE_NAME + "border");
		Widget label = findNamed(universe, TOGGLE_NAME);

		if (session == null || session.baselinePending)
		{
			hide(bg);
			hide(border);
			hide(label);
			restoreTitle();
			return;
		}

		if (bg == null)
		{
			bg = universe.createChild(-1, WidgetType.RECTANGLE);
			bg.setName(TOGGLE_NAME + "bg");
			bg.setFilled(true);
			bg.setTextColor(TOGGLE_BG);
		}
		if (border == null)
		{
			border = universe.createChild(-1, WidgetType.RECTANGLE);
			border.setName(TOGGLE_NAME + "border");
			border.setFilled(false);
			border.setTextColor(TOGGLE_BORDER);
		}
		if (label == null)
		{
			label = universe.createChild(-1, WidgetType.TEXT);
			label.setName(TOGGLE_NAME);
			label.setFontId(FONT_PLAIN_11);
			label.setTextShadowed(true);
			label.setXTextAlignment(WidgetTextAlignment.CENTER);
			label.setYTextAlignment(WidgetTextAlignment.CENTER);
			label.setTextColor(TOGGLE_COLOR);
			label.setHasListener(true);
			final Widget l = label;
			final Widget b = bg;
			label.setOnMouseOverListener((JavaScriptCallback) ev ->
			{
				l.setTextColor(TOGGLE_HOVER_COLOR);
				b.setTextColor(TOGGLE_BG_HOVER);
			});
			label.setOnMouseLeaveListener((JavaScriptCallback) ev ->
			{
				l.setTextColor(TOGGLE_COLOR);
				b.setTextColor(TOGGLE_BG);
			});
			label.setOnOpListener((JavaScriptCallback) ev -> setViewEnabled(!config.showRebuildView()));
		}

		// Sit just right of the bank's "?" button, matching its height
		net.runelite.api.Point uni = universe.getCanvasLocation();
		Widget tut = client.getWidget(InterfaceID.Bankmain.BANK_TUT);
		int x;
		int y;
		int h;
		if (tut != null && !tut.isHidden())
		{
			x = tut.getCanvasLocation().getX() - uni.getX() + tut.getWidth() + 4;
			y = tut.getCanvasLocation().getY() - uni.getY();
			h = tut.getHeight();
		}
		else
		{
			x = 60;
			y = title.getCanvasLocation().getY() - uni.getY();
			h = Math.max(18, title.getHeight());
		}
		for (Widget w : new Widget[]{bg, border, label})
		{
			w.setOriginalX(x);
			w.setOriginalY(y);
			w.setOriginalWidth(TOGGLE_WIDTH);
			w.setOriginalHeight(h);
			w.setHidden(false);
			w.revalidate();
		}

		boolean tempView = config.showRebuildView();
		label.setText(tempView ? "Real bank" : "Temp bank");
		label.setAction(0, tempView ? "Switch to real bank" : "Switch to temporary bank");

		makeRoomInTitle(title, x + TOGGLE_WIDTH + 6 + uni.getX());
	}

	/** The bank title widget we moved, and where it was, so it can be put back. */
	private Widget movedTitle;
	private int movedTitleBaseX;

	/**
	 * Slide the bank's title text right just enough that it starts after our
	 * button, so long titles (e.g. with bank value plugins) are never covered.
	 */
	private void makeRoomInTitle(Widget title, int minTextCanvasX)
	{
		if (title != movedTitle)
		{
			movedTitle = title;
			movedTitleBaseX = title.getOriginalX();
		}
		// measure from the title's original position
		title.setOriginalX(movedTitleBaseX);
		title.revalidate();

		String text = title.getText();
		if (text == null || title.getFont() == null)
		{
			return;
		}
		int plain = text.replaceAll("<[^>]*>", "").length() == 0 ? 0 : title.getFont().getTextWidth(text.replaceAll("<[^>]*>", ""));
		int textLeft = title.getCanvasLocation().getX() + (title.getWidth() - plain) / 2;
		int shift = minTextCanvasX - textLeft;
		if (shift <= 0)
		{
			return;
		}
		// positive originalX moves right, except for right-aligned widgets
		boolean rightAligned = title.getXPositionMode() == WidgetPositionMode.ABSOLUTE_RIGHT;
		title.setOriginalX(movedTitleBaseX + (rightAligned ? -shift : shift));
		title.revalidate();
	}

	private void restoreTitle()
	{
		if (movedTitle != null)
		{
			movedTitle.setOriginalX(movedTitleBaseX);
			movedTitle.revalidate();
			movedTitle = null;
		}
	}

	/** Re-run the bank's own build script so our view is applied or removed. */
	private void rebuildBankInterface()
	{
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null || container.isHidden())
		{
			return;
		}
		Object[] listener = container.getOnInvTransmitListener();
		if (listener != null)
		{
			client.runScript(listener);
		}
	}

	// ------------------------------------------------------------------ helpers

	private RebuildSession.Policy policy()
	{
		final UntradeableMode mode = config.untradeableMode();
		final UntradeableFilter filter = config.untradeableFilter();
		final List<String> always = nameList(config.untradeableAlwaysAllow());
		final List<String> never = nameList(config.untradeableNeverAllow());
		final boolean buyTradeables = config.buyTradeables();
		final long fallback = Math.max(0, Amounts.parse(config.defaultUntradeableCost()));
		final Map<String, Long> custom = mode == UntradeableMode.CUSTOM
			? PerduCosts.parseCustom(config.customUntradeableCosts())
			: java.util.Collections.emptyMap();
		return new RebuildSession.Policy()
		{
			@Override
			public boolean isUntradeable(int itemId)
			{
				return isTrueUntradeable(itemId);
			}

			@Override
			public boolean isUnlockable(int itemId)
			{
				String name = itemManager.getItemComposition(itemId).getName();
				if (matches(never, name))
				{
					return false;
				}
				if (matches(always, name))
				{
					return true;
				}
				switch (filter)
				{
					case PERDU:
						return PerduCosts.cost(name) >= 0;
					case ALL:
						return true;
					case COMBAT:
					default:
						return isCombatGear(itemId, name);
				}
			}

			@Override
			public UntradeableMode untradeableMode()
			{
				return mode;
			}

			@Override
			public long untradeableCost(int itemId)
			{
				String name = itemManager.getItemComposition(itemId).getName();
				switch (mode)
				{
					case REPAIR_COST:
						long perdu = PerduCosts.cost(name);
						return perdu >= 0 ? perdu : fallback;
					case CUSTOM:
						return custom.getOrDefault(name.toLowerCase(Locale.ROOT), fallback);
					default:
						return 0;
				}
			}

			@Override
			public boolean buyTradeables()
			{
				return buyTradeables;
			}

			@Override
			public long tradeablePrice(int itemId)
			{
				return itemManager.getItemPrice(itemId);
			}
		};
	}

	/**
	 * PvM gear: equippable and gives combat stats (skillcapes, void, defenders and
	 * their cosmetic variants, fire/infernal capes...), or something Perdu repairs.
	 * Quest items, clue junk, cosmetics with no stats etc. are left out.
	 */
	private boolean isCombatGear(int itemId, String name)
	{
		if (PerduCosts.cost(name) >= 0)
		{
			return true;
		}
		ItemStats stats = itemManager.getItemStats(itemId);
		if (stats == null || !stats.isEquipable() || stats.getEquipment() == null)
		{
			return false;
		}
		ItemEquipmentStats e = stats.getEquipment();
		return e.getAstab() != 0 || e.getAslash() != 0 || e.getAcrush() != 0 || e.getAmagic() != 0 || e.getArange() != 0
			|| e.getDstab() != 0 || e.getDslash() != 0 || e.getDcrush() != 0 || e.getDmagic() != 0 || e.getDrange() != 0
			|| e.getStr() != 0 || e.getRstr() != 0 || e.getMdmg() != 0 || e.getPrayer() != 0;
	}

	/** "Graceful*, Barrows gloves" -> patterns; a trailing * matches any ending. */
	private static List<String> nameList(String text)
	{
		List<String> out = new ArrayList<>();
		if (text != null)
		{
			for (String part : text.split("[\\n,;]"))
			{
				String t = part.trim().toLowerCase(Locale.ROOT);
				if (!t.isEmpty())
				{
					out.add(t);
				}
			}
		}
		return out;
	}

	private static boolean matches(List<String> patterns, String name)
	{
		String n = name.toLowerCase(Locale.ROOT);
		for (String p : patterns)
		{
			if (p.endsWith("*") ? n.startsWith(p.substring(0, p.length() - 1)) : n.equals(p))
			{
				return true;
			}
		}
		return false;
	}

	/** Value of one item in the temporary bank. */
	private long unitValue(int itemId)
	{
		if (itemId == ItemID.COINS)
		{
			return 1;
		}
		ItemComposition def = itemManager.getItemComposition(itemId);
		// Tradeables, and untradeable versions of them (charged/degraded/attached
		// gear like a tentacle, bowfa, degraded barrows, charged Tumeken's shadow),
		// which RuneLite prices from their tradeable parts.
		long price = itemManager.getItemPrice(itemId);
		if (def.isTradeable() || price > 0)
		{
			return price;
		}
		return config.valueUntradeables() ? def.getHaPrice() : 0;
	}

	/**
	 * Untradeable and not just a charged/degraded/combined version of tradeable
	 * items. Those (abyssal tentacle, crystal gear, degraded barrows, charged
	 * weapons...) have a price in RuneLite and are treated as normal bank items.
	 */
	private boolean isTrueUntradeable(int itemId)
	{
		return itemId != ItemID.COINS
			&& !itemManager.getItemComposition(itemId).isTradeable()
			&& itemManager.getItemPrice(itemId) <= 0;
	}

	private Map<Integer, Long> snapshot(ItemContainer container)
	{
		Map<Integer, Long> map = new HashMap<>();
		if (container == null)
		{
			return map;
		}
		for (Item item : container.getItems())
		{
			int id = item.getId();
			if (id <= 0 || item.getQuantity() <= 0 || id == ItemID.BANK_FILLER)
			{
				continue;
			}
			if (client.getItemDefinition(id).getPlaceholderTemplateId() >= 0)
			{
				continue;
			}
			map.merge(itemManager.canonicalize(id), (long) item.getQuantity(), Long::sum);
		}
		return map;
	}

	private void addContainer(Map<Integer, Long> into, int containerId)
	{
		snapshot(client.getItemContainer(containerId)).forEach((id, q) -> into.merge(id, q, Long::sum));
	}

	/** Amount a withdraw option will take, or -1 if unknown (Withdraw-X prompt). */
	private long requestedAmount(String option, long realCount)
	{
		String suffix = option.substring("Withdraw".length()).replaceFirst("^-", "").trim();
		switch (suffix.toLowerCase(Locale.ROOT))
		{
			case "all":
				return realCount;
			case "all-but-1":
				return Math.max(0, realCount - 1);
			case "x":
				return -1;
			case "":
				return client.getVarbitValue(VarbitID.BANK_REQUESTEDQUANTITY);
			default:
				try
				{
					return Long.parseLong(suffix);
				}
				catch (NumberFormatException ex)
				{
					return -1;
				}
		}
	}

	private int resolveItem(String text)
	{
		if (text == null || text.isEmpty())
		{
			return -1;
		}
		try
		{
			int id = Integer.parseInt(text);
			return id > 0 ? itemManager.canonicalize(id) : -1;
		}
		catch (NumberFormatException ignored)
		{
			// not an id
		}
		return itemManager.search(text).stream()
			.min(Comparator.comparing(p -> !p.getName().equalsIgnoreCase(text)))
			.map(p -> p.getId())
			.orElse(-1);
	}

	private void checkGoal()
	{
		if (session == null || !session.hasGoal() || session.isComplete() || session.baselinePending)
		{
			return;
		}
		if (session.goalProgress(this::unitValue, ItemID.COINS) >= session.goalTarget)
		{
			session.completedAtTicks = session.ticks;
			long net = session.netValue(this::unitValue);
			String msg = "Goal reached in " + RebuildSession.formatTicks(session.completedAtTicks)
				+ " of in-game time! Net value " + Amounts.format(net) + " gp ("
				+ Amounts.format(RebuildSession.gpPerHour(net, session.completedAtTicks)) + " gp/hr).";
			chat(msg);
			if (config.notifyGoal())
			{
				notifier.notify(msg);
			}
		}
	}

	private void reportChanges(List<RebuildSession.Change> changes)
	{
		for (RebuildSession.Change c : changes)
		{
			String name = itemManager.getItemComposition(c.itemId).getName();
			if (c.violated > 0)
			{
				chat(ColorUtil.wrapWithColorTag("You owe " + Amounts.format(c.violated) + " x " + name + "!", Color.RED)
					+ " That came out of your real bank - deposit it back.", true);
			}
			if (c.repaid > 0)
			{
				long left = session != null ? session.getOwed().getOrDefault(c.itemId, 0L) : 0;
				chat("Paid back " + Amounts.format(c.repaid) + " x " + name + " to your real bank"
					+ (left > 0 ? " - still owe " + Amounts.format(left) + "." : ". All square!"), true);
			}
			if (c.unlocked > 0 && c.unlockCost > 0)
			{
				chat("Paid " + Amounts.format(c.unlockCost) + " gp for " + Amounts.format(c.unlocked) + " x " + name + ".", true);
			}
			if (!config.chatMessages())
			{
				continue;
			}
			if (c.bought > 0)
			{
				chat("Bought " + Amounts.format(c.bought) + " x " + name + " from your hidden real bank for "
					+ Amounts.format(c.boughtCost) + " gp.");
			}
			if (c.unlocked > 0 && c.unlockCost == 0)
			{
				chat("Took " + Amounts.format(c.unlocked) + " x " + name + " from your existing untradeables.");
			}
			if (c.returned > 0)
			{
				chat("Returned " + Amounts.format(c.returned) + " x " + name + " to your hidden real bank.");
			}
		}
	}

	private void chat(String message)
	{
		chat(message, false);
	}

	private void chat(String message, boolean always)
	{
		if (!always && !config.chatMessages() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(ColorUtil.wrapWithColorTag("[Temp Bank] ", CHAT_COLOR) + message)
			.build());
	}

	private void loadSession()
	{
		session = null;
		profileKey = configManager.getRSProfileKey();
		if (profileKey == null)
		{
			return;
		}
		String json = configManager.getConfiguration(RebuildBankConfig.GROUP, profileKey, SESSION_KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			session = gson.fromJson(json, RebuildSession.class);
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Couldn't read saved rebuild session", ex);
		}
	}

	private void saveSession()
	{
		if (session == null || profileKey == null)
		{
			return;
		}
		configManager.setConfiguration(RebuildBankConfig.GROUP, profileKey, SESSION_KEY, gson.toJson(session));
	}

	/** Builds a snapshot on the client thread and hands it to the panel. */
	private void refreshPanel()
	{
		if (panel == null)
		{
			return;
		}
		PanelData data = buildPanelData();
		RebuildBankPanel p = panel;
		SwingUtilities.invokeLater(() -> p.update(data));
	}

	private PanelData buildPanelData()
	{
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (!loggedIn && session == null)
		{
			return PanelData.LOGGED_OUT;
		}
		if (session == null)
		{
			return PanelData.builder().loggedIn(true)
				.status("No temporary bank running.").build();
		}

		List<PanelData.Row> rows = new ArrayList<>();
		long bankValue = 0;
		for (Map.Entry<Integer, Long> e : session.getItems().entrySet())
		{
			long v = unitValue(e.getKey()) * e.getValue();
			bankValue += v;
			rows.add(new PanelData.Row(itemManager.getItemComposition(e.getKey()).getName(), e.getValue(), v));
		}
		rows.sort(Comparator.comparingLong(PanelData.Row::getValue).reversed()
			.thenComparing(PanelData.Row::getName));

		long net = bankValue - session.spent;
		long ticks = session.challengeTicks();
		cachedNetValue = net;
		cachedOwedValue = session.owedValue(this::unitValue);
		cachedGoalProgress = session.goalProgress(this::unitValue, ItemID.COINS);
		String status;
		if (session.baselinePending)
		{
			status = "Open your bank to begin.";
		}
		else if (session.isComplete())
		{
			status = "Goal complete! Timer stopped.";
		}
		else
		{
			status = "Running (in-game time only).";
		}

		return PanelData.builder()
			.loggedIn(loggedIn)
			.active(true)
			.status(status)
			.time(RebuildSession.formatTicks(ticks))
			.bankValue(bankValue)
			.spent(session.paid)
			.debt(session.spent + cachedOwedValue)
			.netValue(net)
			.gpPerHour(RebuildSession.gpPerHour(net, ticks))
			.goalType(session.goalType)
			.goalTarget(session.goalTarget)
			.goalProgress(session.goalProgress(this::unitValue, ItemID.COINS))
			.goalItemName(session.goalItemName)
			.complete(session.isComplete())
			.violations(session.violations)
			.pkKills(session.pkKills)
			.pkAverage(session.averageKillValue())
			.items(rows)
			.build();
	}

}
