package com.rebuildbank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 *   - items that went IN first repay anything borrowed from the old bank,
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
		public long returned;
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
	long ticks;
	/** Tick count when the goal was reached, or -1. */
	long completedAtTicks = -1;
	/** True until the first real bank snapshot after starting has been taken. */
	boolean baselinePending;
	Map<Integer, Long> items = new HashMap<>();
	Map<Integer, Long> borrowed = new HashMap<>();
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
	 * Tradeables carried at the start belong to the old bank (borrowed, so
	 * depositing them returns them). Untradeables you're carrying are yours:
	 * depositing them adds them to the temporary bank.
	 */
	public void borrowCarried(Map<Integer, Long> carried, Policy policy)
	{
		for (Map.Entry<Integer, Long> e : carried.entrySet())
		{
			if (!policy.isUntradeable(e.getKey()))
			{
				add(borrowed, e.getKey(), e.getValue());
			}
		}
	}

	/**
	 * Apply a fresh view of the real bank. The first call after starting only
	 * records the baseline (everything already there is "old bank").
	 */
	public List<Change> applyRealBank(Map<Integer, Long> real, Policy policy)
	{
		if (untradeablePool == null)
		{
			untradeablePool = new HashMap<>(); // sessions saved by older versions
		}

		if (baselinePending)
		{
			lastRealBank = new HashMap<>(real);
			// added to (not replacing) the untradeables you were carrying at the start
			real.forEach((id, qty) ->
			{
				if (policy.isUntradeable(id))
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
				long repay = Math.min(get(borrowed, id), delta);
				add(borrowed, id, -repay);
				add(items, id, delta - repay);
				c.returned = repay;
				c.deposited = delta - repay;
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
						unlockedTypes().add(id);
						pay(c.unlockCost);
						rest -= fromPool;
					}
				}

				if (rest > 0 && !policy.isUntradeable(id) && policy.buyTradeables())
				{
					c.bought = rest;
					c.boughtCost = policy.tradeablePrice(id) * rest;
					pay(c.boughtCost);
					rest = 0;
				}

				if (rest > 0)
				{
					add(borrowed, id, rest);
					c.violated = rest;
					violations++;
				}
			}
			changes.add(c);
		}

		lastRealBank = new HashMap<>(real);
		return changes;
	}

	private static boolean canUnlock(int id, Policy policy)
	{
		return policy.untradeableMode() != UntradeableMode.NOT_ALLOWED
			&& policy.isUntradeable(id) && policy.isUnlockable(id);
	}

	/** Existing untradeables you could unlock right now (id -> quantity). */
	public Map<Integer, Long> unlockable(Policy policy)
	{
		Map<Integer, Long> out = new HashMap<>();
		if (untradeablePool != null)
		{
			untradeablePool.forEach((id, qty) ->
			{
				if (canUnlock(id, policy))
				{
					out.put(id, qty);
				}
			});
		}
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
		unlockedTypes().add(itemId);
		pay(c.unlockCost);
		return c;
	}

	/** One fee per item type (the whole stack), and nothing once it's been paid. */
	public long unlockFee(int itemId, Policy policy)
	{
		return unlockedTypes().contains(itemId) ? 0 : policy.untradeableCost(itemId);
	}

	private Set<Integer> unlockedTypes()
	{
		if (unlockedTypes == null)
		{
			unlockedTypes = new HashSet<>(); // sessions saved by older versions
		}
		return unlockedTypes;
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
		return untradeablePool == null ? 0 : get(untradeablePool, itemId);
	}

	/** How many of an item can be withdrawn without breaking the rules (Long.MAX_VALUE = unlimited). */
	public long withdrawable(int itemId, Policy policy)
	{
		if (!policy.isUntradeable(itemId) && policy.buyTradeables())
		{
			return Long.MAX_VALUE;
		}
		return count(itemId);
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

	public long netValue(IntToLongFunction unitPrice)
	{
		return bankValue(unitPrice) - spent;
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

	public long totalBorrowed()
	{
		return borrowed.values().stream().mapToLong(Long::longValue).sum();
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
