package com.rebuildbank;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class RebuildSessionTest
{
	private static final int COINS = 995, WHIP = 4151, FIRE_CAPE = 6570, LOBSTER = 379, TORSO = 10551;
	private static final Set<Integer> UNTRADEABLE = Set.of(FIRE_CAPE, TORSO);

	private static Map<Integer, Long> bank(long... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((int) pairs[i], pairs[i + 1]);
		}
		return m;
	}

	private static RebuildSession.Policy policy(UntradeableMode mode, long untradeableCost, boolean buyTradeables, long tradeablePrice)
	{
		return new RebuildSession.Policy()
		{
			public boolean isUntradeable(int id)
			{
				return UNTRADEABLE.contains(id);
			}

			public boolean isUnlockable(int id)
			{
				return true;
			}

			public UntradeableMode untradeableMode()
			{
				return mode;
			}

			public long untradeableCost(int id)
			{
				return untradeableCost;
			}

			public boolean buyTradeables()
			{
				return buyTradeables;
			}

			public long tradeablePrice(int id)
			{
				return tradeablePrice;
			}
		};
	}

	private static final RebuildSession.Policy STRICT = policy(UntradeableMode.NOT_ALLOWED, 0, false, 0);

	@Test
	public void startsEmptyAndTracksDeposits()
	{
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(COINS, 50_000_000, WHIP, 3), STRICT);
		assertTrue(s.getItems().isEmpty());
		assertTrue(s.isTiming());

		s.applyRealBank(bank(COINS, 50_000_500, WHIP, 3, LOBSTER, 20), STRICT);
		assertEquals(500, s.count(COINS));
		assertEquals(20, s.count(LOBSTER));
		assertEquals(0, s.count(WHIP));
	}

	@Test
	public void withdrawComesFromTempFirstThenFlagsOverdraw()
	{
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(LOBSTER, 100), STRICT);
		s.applyRealBank(bank(LOBSTER, 105), STRICT); // deposit 5
		List<RebuildSession.Change> c = s.applyRealBank(bank(LOBSTER, 90), STRICT); // withdraw 15
		assertEquals(0, s.count(LOBSTER));
		assertEquals(5, c.get(0).withdrawn);
		assertEquals(10, c.get(0).violated);
		assertEquals(1, s.violations);
		assertEquals(10, s.totalBorrowed());

		s.applyRealBank(bank(LOBSTER, 100), STRICT); // deposit them back
		assertEquals(0, s.count(LOBSTER));
		assertEquals(0, s.totalBorrowed());
	}

	@Test
	public void carriedTradeablesAreReturnedNotCounted()
	{
		RebuildSession s = RebuildSession.start(0);
		s.borrowCarried(bank(WHIP, 1, COINS, 1000), STRICT);
		s.applyRealBank(bank(), STRICT);
		s.applyRealBank(bank(WHIP, 1, COINS, 1500), STRICT);
		assertEquals(0, s.count(WHIP));
		assertEquals(500, s.count(COINS));
	}

	@Test
	public void existingUntradeablesNotAllowed()
	{
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(FIRE_CAPE, 1), STRICT);
		assertEquals(0, s.withdrawable(FIRE_CAPE, STRICT));
		s.applyRealBank(bank(), STRICT);
		assertEquals(1, s.violations);
	}

	@Test
	public void existingUntradeablesFreeUnlock()
	{
		RebuildSession.Policy free = policy(UntradeableMode.ALLOWED, 0, false, 0);
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(FIRE_CAPE, 1, TORSO, 1), free);
		assertEquals(1, s.unlockable(free).get(FIRE_CAPE).longValue());
		assertEquals(0, s.withdrawable(FIRE_CAPE, free)); // locked until unlocked

		RebuildSession.Change c = s.unlock(FIRE_CAPE, free);
		assertEquals(1, c.unlocked);
		assertEquals(1, s.count(FIRE_CAPE));
		assertEquals(0, s.poolCount(FIRE_CAPE));

		s.applyRealBank(bank(TORSO, 1), free); // withdraw it normally now
		assertEquals(0, s.violations);
		assertEquals(0, s.count(FIRE_CAPE));
	}

	@Test
	public void paidUnlockNeedsCoinsAndNeverGoesNegative()
	{
		RebuildSession.Policy paid = policy(UntradeableMode.REPAIR_COST, 150_000, false, 0);
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(FIRE_CAPE, 1), paid);

		RebuildSession.Change broke = s.unlock(FIRE_CAPE, paid);
		assertEquals(0, broke.unlocked); // can't afford
		assertEquals(0, s.count(FIRE_CAPE));
		assertEquals(0, s.netValue(id -> id == RebuildSession.COINS ? 1 : 0));

		s.applyRealBank(bank(FIRE_CAPE, 1, COINS, 200_000), paid); // deposit 200k
		RebuildSession.Change ok = s.unlock(FIRE_CAPE, paid);
		assertEquals(1, ok.unlocked);
		assertEquals(50_000, s.count(COINS));
		assertEquals(0, s.spent); // no debt
		assertEquals(150_000, s.paid);
		assertEquals(50_000, s.netValue(id -> id == RebuildSession.COINS ? 1 : 0));

		// the paid coins are old bank now: withdrawing 50k is fine, more is a rule break
		s.applyRealBank(bank(FIRE_CAPE, 1, COINS, 150_000), paid);
		assertEquals(0, s.violations);
	}

	@Test
	public void untradeablesYouBringOrEarnAreYours()
	{
		RebuildSession.Policy paid = policy(UntradeableMode.CUSTOM, 1_000_000, false, 0);
		RebuildSession s = RebuildSession.start(0);
		s.borrowCarried(bank(TORSO, 1, WHIP, 1), paid); // wearing a torso and a whip
		s.applyRealBank(bank(FIRE_CAPE, 1), paid);
		s.applyRealBank(bank(FIRE_CAPE, 2, TORSO, 1, WHIP, 1), paid); // bank them + a newly earned fire cape
		assertEquals(1, s.count(TORSO));     // untradeable you brought: yours
		assertEquals(1, s.count(FIRE_CAPE)); // the one you earned: yours
		assertEquals(0, s.count(WHIP));      // tradeable you brought: old bank
		assertEquals(1, s.poolCount(FIRE_CAPE)); // the old one is still locked
		assertEquals(0, s.spent);
	}

	@Test
	public void unlockIsOncePerTypeAndPermanent()
	{
		RebuildSession.Policy paid = policy(UntradeableMode.CUSTOM, 1_000_000, false, 0);
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(TORSO, 6), paid);
		s.applyRealBank(bank(TORSO, 6, COINS, 1_500_000), paid);
		assertEquals(1_000_000, s.unlockFee(TORSO, paid)); // not 6m
		RebuildSession.Change c = s.unlock(TORSO, paid);
		assertEquals(6, c.unlocked);
		assertEquals(6, s.count(TORSO));
		assertEquals(500_000, s.count(COINS));
		assertEquals(0, s.unlockFee(TORSO, paid)); // paid for good
	}

	@Test
	public void killStats()
	{
		RebuildSession s = RebuildSession.start(0);
		s.recordKill(2_000_000);
		s.recordKill(0); // loot key kill
		s.addKillLoot(1_000_000); // key opened at the chest
		assertEquals(2, s.pkKills);
		assertEquals(1_500_000, s.averageKillValue());
	}

	@Test
	public void buyingTradeablesCharges()
	{
		RebuildSession.Policy buy = policy(UntradeableMode.NOT_ALLOWED, 0, true, 25_000);
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(WHIP, 1), buy);
		assertEquals(Long.MAX_VALUE, s.withdrawable(WHIP, buy));
		s.applyRealBank(bank(), buy);
		assertEquals(25_000, s.spent); // no coins, so it's debt
		s.applyRealBank(bank(WHIP, 1), buy);
		assertEquals(1, s.count(WHIP));
		assertEquals(0, s.netValue(id -> id == WHIP ? 25_000 : 0));
	}

	@Test
	public void goalAndGpPerHour()
	{
		RebuildSession s = RebuildSession.start(0);
		s.goalType = GoalType.NET_VALUE;
		s.goalTarget = 1_000_000;
		s.applyRealBank(bank(), STRICT);
		s.ticks = 6000; // 1 hour
		s.applyRealBank(bank(COINS, 1_000_000), STRICT);
		assertTrue(s.goalProgress(id -> 1, COINS) >= s.goalTarget);
		assertEquals(1_000_000, RebuildSession.gpPerHour(1_000_000, s.ticks));
		assertEquals("1:00:00", RebuildSession.formatTicks(s.ticks));
	}

	@Test
	public void baselinePendingDoesNotTime()
	{
		assertFalse(RebuildSession.start(0).isTiming());
	}

	@Test
	public void parsesAmountsAndCustomCosts()
	{
		assertEquals(10_000_000, Amounts.parse("10m"));
		assertEquals(2_500_000_000L, Amounts.parse("2.5b"));
		assertEquals(500_000, Amounts.parse("500K"));
		assertEquals(1_234_567, Amounts.parse("1,234,567"));
		assertEquals(-1, Amounts.parse("abc"));

		Map<String, Long> custom = PerduCosts.parseCustom("Fire cape: 1m\nInfernal cape=5m, Fighter torso: 250k");
		assertEquals(Long.valueOf(1_000_000), custom.get("fire cape"));
		assertEquals(Long.valueOf(5_000_000), custom.get("infernal cape"));
		assertEquals(Long.valueOf(250_000), custom.get("fighter torso"));
	}

	@Test
	public void perduTableLoads()
	{
		assertEquals(150_000, PerduCosts.cost("Fire cape"));
		assertEquals(600_000, PerduCosts.cost("avernic defender"));
		assertEquals(-1, PerduCosts.cost("Coins"));
	}
}
