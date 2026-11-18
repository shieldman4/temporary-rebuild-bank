package com.rebuildbank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntToLongFunction;
import net.runelite.api.gameval.ItemID;

/**
 * One temporary bank run. Pure data + ledger logic (no RuneLite types) so it can be
 * persisted with Gson and unit tested.
 *
 * The real bank is never modified. Every time the real bank is seen, it is
 * diffed against the last real bank we saw:
 *   - items that went IN first pay back anything owed to the real bank,
 *     the rest becomes part of the temporary bank;
 *   - items that went OUT come from the temporary bank first, then from the
 *     pool of untradeables that existed when the run started (if allowed, and
 *     possibly for a fee), then from the old bank (bought, or a rule break).
 */
public class RebuildSession
{
	public static final int TICK_MILLIS = 600;
	public static final int COINS = ItemID.COINS;

	public interface Policy
	{
		boolean isUntradeable(int itemId);

		/**
		 * Untradeables that always belong to the temporary bank, never locked or owed
		 * (the looting bag: you can only own one, so you couldn't get another).
		 */
		default boolean alwaysYours(int itemId)
		{
			return false;
		}

		/** Whether an existing untradeable may be unlocked (e.g. PvM gear only). */
		boolean isUnlockable(int itemId);

		UntradeableMode untradeableMode();

		/** Fee for taking one existing untradeable, under the current mode. */
		long untradeableCost(int itemId);

		/** Whether tradeables missing from the temporary bank may be bought from the old bank. */
		boolean buyTradeables();

		long tradeablePrice(int itemId);
	}

	/** What happened to one item in one bank update; used for chat messages. */
	public static final class Change
	{
		public final int itemId;
		public long deposited;
		public long repaid;
		/** Untradeables you started with, deposited: they join the locked untradeables. */
		public long locked;
		public long withdrawn;
		public long unlocked;
		public long unlockCost;
		public long bought;
		public long boughtCost;
		public long violated;

		Change(int itemId)
		{
			this.itemId = itemId;
		}
	}

	// ---- persisted state (Gson) ----
	long startedAtMillis;
	/** When this run was last saved; used to pick the newest of the settings copy and the backup file. */
	long savedAtMillis;
	long ticks;
	/** Tick count when the goal was reached, or -1. */
	long completedAtTicks = -1;
	/** True until the first real bank snapshot after starting has been taken. */
	boolean baselinePending;
	Map<Integer, Long> items = new HashMap<>();
	/**
	 * Owed to the real bank and shown in red until deposited back: tradeables you were
	 * carrying at the start, and anything taken out beyond what the temporary bank had.
	 */
	Map<Integer, Long> owed = new HashMap<>();
	/**
	 * Untradeables you were carrying when the run started and haven't deposited yet.
	 * They're pre-run items, so once deposited they're locked like the ones in your bank.
	 */
	Map<Integer, Long> carriedLocked = new HashMap<>();
	/** Untradeables that were in the real bank when the run started. */
	Map<Integer, Long> untradeablePool = new HashMap<>();
	Map<Integer, Long> lastRealBank = new HashMap<>();
	/** Unpaid debt (only when something was taken that your coins couldn't cover). */
	long spent;
	/** Total paid for unlocks and buys, from coins or debt. */
	long paid;
	long violations;
	/** Item types already paid for: unlocking is once per type, and permanent. */
	Set<Integer> unlockedTypes = new HashSet<>();
	long pkKills;
	long pkLootValue;
	GoalType goalType = GoalType.NONE;
	long goalTarget;
	int goalItemId = -1;
	String goalItemName;

	public static RebuildSession start(long nowMillis)
	{
		RebuildSession s = new RebuildSession();
		s.startedAtMillis = nowMillis;
		s.baselinePending = true;
		return s;
	}

	/**
	 * Everything you're carrying when the run starts is pre-run stuff. Tradeables came
	 * from your real bank, so they're owed until you deposit them back (using them up
	 * keeps them owed until replaced). Untradeables are locked: when you deposit them
	 * they join the locked untradeables, to unlock under your unlock settings.
	 * Untradeables you get during the run are yours. Both kinds show as owed until
	 * deposited. Returns how many items became owed.
	 */
	public long oweCarried(Map<Integer, Long> carried, Policy policy)
	{
		long total = 0;
		for (Map.Entry<Integer, Long> e : carried.entrySet())
		{
			if (policy.alwaysYours(e.getKey()))
			{
				continue; // yours: depositing it just adds it to the temporary bank
			}
			add(policy.isUntradeable(e.getKey()) ? carriedLocked : owed, e.getKey(), e.getValue());
			total += e.getValue();
		}
		return total;
	}

	/**
	 * Apply a fresh view of the real bank. The first call after starting only
	 * records the baseline (everything already there is "old bank").
	 */
	public List<Change> applyRealBank(Map<Integer, Long> real, Policy policy)
	{
		if (baselinePending)
		{
			lastRealBank = new HashMap<>(real);
			// added to (not replacing) the untradeables you were carrying at the start
			real.forEach((id, qty) ->
			{
				if (policy.alwaysYours(id))
				{
					add(items, id, qty); // already in the bank, and it's yours
				}
				else if (policy.isUntradeable(id))
				{
					add(untradeablePool, id, qty);
				}
			});
			baselinePending = false;
			return Collections.emptyList();
		}

		List<Change> changes = new ArrayList<>();
		Set<Integer> ids = new HashSet<>(real.keySet());
		ids.addAll(lastRealBank.keySet());

		for (int id : ids)
		{
			long delta = real.getOrDefault(id, 0L) - lastRealBank.getOrDefault(id, 0L);
			if (delta == 0)
			{
				continue;
			}

			Change c = new Change(id);
			if (delta > 0)
			{
				long rest = delta;
				long repaidOwed = Math.min(get(owed, id), rest);
				add(owed, id, -repaidOwed);
				rest -= repaidOwed;
				// untradeables you started with go to the locked section, not to you
				long locking = Math.min(get(carriedLocked, id), rest);
				add(carriedLocked, id, -locking);
				add(untradeablePool, id, locking);
				rest -= locking;
				add(items, id, rest);
				c.repaid = repaidOwed;
				c.locked = locking;
				c.deposited = rest;
			}
			else
			{
				long rest = -delta;

				long fromTemp = Math.min(get(items, id), rest);
				add(items, id, -fromTemp);
				c.withdrawn = fromTemp;
				rest -= fromTemp;

				// Backup only: normally locked items are blocked until unlocked from the bank
				if (rest > 0 && canUnlock(id, policy))
				{
					long fromPool = Math.min(get(untradeablePool, id), rest);
					if (fromPool > 0)
					{
						add(untradeablePool, id, -fromPool);
						c.unlocked = fromPool;
						c.unlockCost = unlockFee(id, policy);
						unlockedTypes.add(id);
						pay(c.unlockCost);
						rest -= fromPool;
					}
				}

				if (rest > 0 && canBuy(id, policy))
				{
					// only as many as the temporary bank's coins can pay for
					long price = policy.tradeablePrice(id);
					long affordable = Math.min(rest, count(COINS) / price);
					if (affordable > 0)
					{
						c.bought = affordable;
						c.boughtCost = price * affordable;
						pay(c.boughtCost);
						rest -= affordable;
					}
				}

				if (rest > 0)
				{
					add(owed, id, rest);
					c.violated = rest;
					violations++;
				}
			}
			changes.add(c);
		}

		lastRealBank = new HashMap<>(real);
		return changes;
	}

	private static boolean canBuy(int id, Policy policy)
	{
		return id != COINS && !policy.isUntradeable(id) && policy.buyTradeables() && policy.tradeablePrice(id) > 0;
	}


	/**
	 * Everything that has to go back into the bank (id -> quantity): tradeables owed to the
	 * real bank, plus untradeables you started with that haven't been deposited yet (those
	 * go to the locked section when deposited).
	 */
	public Map<Integer, Long> getOwed()
	{
		Map<Integer, Long> all = new HashMap<>(owed);
		carriedLocked.forEach((id, qty) -> all.merge(id, qty, Long::sum));
		return Collections.unmodifiableMap(all);
	}

	public long owedValue(IntToLongFunction unitPrice)
	{
		long total = 0;
		for (Map.Entry<Integer, Long> e : getOwed().entrySet())
		{
			total += unitPrice.applyAsLong(e.getKey()) * e.getValue();
		}
		return total;
	}

	private static boolean canUnlock(int id, Policy policy)
	{
		return policy.untradeableMode() != UntradeableMode.NOT_ALLOWED
			&& policy.isUntradeable(id) && policy.isUnlockable(id);
	}

	/** Whether this item is a locked existing untradeable that can be unlocked right now. */
	public boolean canUnlockNow(int itemId, Policy policy)
	{
		return poolCount(itemId) > 0 && canUnlock(itemId, policy);
	}

	/** Existing untradeables you could unlock right now (id -> quantity). */
	public Map<Integer, Long> unlockable(Policy policy)
	{
		Map<Integer, Long> out = new HashMap<>();
		untradeablePool.forEach((id, qty) ->
		{
			if (canUnlock(id, policy))
			{
				out.put(id, qty);
			}
		});
		return out;
	}

	/**
	 * Unlock an existing untradeable: it moves from the old bank into the
	 * temporary bank (it never leaves the real bank), paying the fee if any.
	 * Returns null if it can't be unlocked, or a change with unlocked == 0 if
	 * the temporary bank doesn't have enough coins.
	 */
	public Change unlock(int itemId, Policy policy)
	{
		long qty = poolCount(itemId);
		if (qty <= 0 || !canUnlock(itemId, policy))
		{
			return null;
		}
		Change c = new Change(itemId);
		c.unlocked = qty;
		c.unlockCost = unlockFee(itemId, policy);
		if (c.unlockCost > count(COINS))
		{
			c.unlocked = 0; // can't afford it
			return c;
		}
		untradeablePool.remove(itemId);
		add(items, itemId, qty);
		unlockedTypes.add(itemId);
		pay(c.unlockCost);
		return c;
	}

	/** One fee per item type (the whole stack), and nothing once it's been paid. */
	public long unlockFee(int itemId, Policy policy)
	{
		return unlockedTypes.contains(itemId) ? 0 : policy.untradeableCost(itemId);
	}


	public void recordKill(long lootValue)
	{
		pkKills++;
		pkLootValue += Math.max(0, lootValue);
	}

	public void addKillLoot(long lootValue)
	{
		pkLootValue += Math.max(0, lootValue);
	}

	public long averageKillValue()
	{
		return pkKills <= 0 ? 0 : pkLootValue / pkKills;
	}

	/**
	 * Pay from the coins in the temporary bank. The coins stay in your real
	 * bank but go back to being "old bank". Anything coins can't cover is debt.
	 */
	private void pay(long cost)
	{
		if (cost <= 0)
		{
			return;
		}
		long fromCoins = Math.min(count(COINS), cost);
		add(items, COINS, -fromCoins);
		spent += cost - fromCoins;
		paid += cost;
	}

	/** Items you own in the temporary bank. */
	public long count(int itemId)
	{
		return get(items, itemId);
	}

	/** Existing untradeables still waiting in the old bank. */
	public long poolCount(int itemId)
	{
		return get(untradeablePool, itemId);
	}

	/** How many of an item can be withdrawn without breaking the rules (Long.MAX_VALUE = unlimited). */
	public long withdrawable(int itemId, Policy policy)
	{
		long n = count(itemId);
		if (canBuy(itemId, policy))
		{
			// plus as many as the temporary bank's coins can buy
			n += count(COINS) / policy.tradeablePrice(itemId);
		}
		return n;
	}

	/**
	 * How many a bank "Withdraw-..." option takes, or -1 when it isn't known up front
	 * (Withdraw-X opens a prompt). "Withdraw-500" is the remembered last-X amount.
	 */
	public static long requestedAmount(String option, long realCount)
	{
		String suffix = option.substring("Withdraw".length()).replaceFirst("^-", "").trim();
		switch (suffix.toLowerCase(Locale.ROOT))
		{
			case "all":
				return realCount;
			case "all-but-1":
				return Math.max(0, realCount - 1);
			default:
				try
				{
					return Long.parseLong(suffix);
				}
				catch (NumberFormatException ex)
				{
					return -1; // Withdraw-X, or anything unexpected
				}
		}
	}

	/**
	 * Whether a withdraw would take more than the temporary bank allows. When the
	 * amount isn't known yet (Withdraw-X), only block if nothing at all is allowed.
	 */
	public static boolean overdraws(long wanted, long realCount, long allowed)
	{
		return wanted < 0 ? allowed <= 0 : Math.min(wanted, realCount) > allowed;
	}

	public long bankValue(IntToLongFunction unitPrice)
	{
		long total = 0;
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			total += unitPrice.applyAsLong(e.getKey()) * e.getValue();
		}
		return total;
	}

	/**
	 * What the run is worth: the temporary bank's value minus anything owed to the real
	 * bank, never below 0. Items bought with owed (real-bank) coins don't count until
	 * the debt is paid back.
	 */
	public long netValue(IntToLongFunction unitPrice)
	{
		return Math.max(0, bankValue(unitPrice) - spent - owedValue(unitPrice));
	}

	/** Current progress towards the goal, in the goal's own unit. */
	public long goalProgress(IntToLongFunction unitPrice, int coinsId)
	{
		switch (goalType)
		{
			case NET_VALUE:
				return netValue(unitPrice);
			case COINS:
				return count(coinsId);
			case ITEM:
				return goalItemId >= 0 ? count(goalItemId) : 0;
			default:
				return 0;
		}
	}

	public boolean hasGoal()
	{
		return goalType != GoalType.NONE && goalTarget > 0;
	}

	public boolean isComplete()
	{
		return completedAtTicks >= 0;
	}

	/** Ticks counted towards the challenge (frozen once the goal is reached). */
	public long challengeTicks()
	{
		return isComplete() ? completedAtTicks : ticks;
	}

	public boolean isTiming()
	{
		return !baselinePending && !isComplete();
	}

	public static long gpPerHour(long value, long ticks)
	{
		if (ticks <= 0)
		{
			return 0;
		}
		return (long) (value * 3_600_000.0 / (ticks * (double) TICK_MILLIS));
	}

	public static String formatTicks(long ticks)
	{
		return formatSeconds(ticks * TICK_MILLIS / 1000);
	}

	public static String formatSeconds(long secs)
	{
		return String.format("%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
	}

	public Map<Integer, Long> getItems()
	{
		return Collections.unmodifiableMap(items);
	}

	private static long get(Map<Integer, Long> m, int id)
	{
		return m.getOrDefault(id, 0L);
	}

	private static void add(Map<Integer, Long> m, int id, long qty)
	{
		long v = m.getOrDefault(id, 0L) + qty;
		if (v <= 0)
		{
			m.remove(id);
		}
		else
		{
			m.put(id, v);
		}
	}
}
