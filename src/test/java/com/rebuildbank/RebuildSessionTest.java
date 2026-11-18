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
	private static final int MOONCLAN_HAT = 9069;
	private static final Set<Integer> UNTRADEABLE = Set.of(FIRE_CAPE, TORSO, MOONCLAN_HAT);

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
		assertEquals(10, s.getOwed().get(LOBSTER).longValue());

		List<RebuildSession.Change> back = s.applyRealBank(bank(LOBSTER, 100), STRICT); // deposit them back
		assertEquals(10, back.get(0).repaid);
		assertEquals(0, s.count(LOBSTER));
		assertTrue(s.getOwed().isEmpty());
	}

	@Test
	public void withdrawAllCoinsThenPutBackOnlyCountsWhatWasYours()
	{
		// the reported bug: 30k in the temp bank, 22m in the real bank
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(COINS, 22_000_000), STRICT);
		s.applyRealBank(bank(COINS, 22_030_000), STRICT); // deposit 30k
		assertEquals(30_000, s.count(COINS));

		s.applyRealBank(bank(), STRICT); // Withdraw-All took everything
		assertEquals(0, s.count(COINS));
		assertEquals(22_000_000, s.getOwed().get(COINS).longValue());
		assertEquals(0, s.netValue(id -> 1)); // never negative

		s.applyRealBank(bank(COINS, 22_030_000), STRICT); // put it all back
		assertEquals(30_000, s.count(COINS)); // not 22m
		assertTrue(s.getOwed().isEmpty());
	}

	@Test
	public void coinsAreNeverBoughtAndBuysNeverGoIntoDebt()
	{
		RebuildSession.Policy buy = policy(UntradeableMode.NOT_ALLOWED, 0, true, 25_000);
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(COINS, 1_000_000, WHIP, 3), buy);
		s.applyRealBank(bank(COINS, 1_030_000, WHIP, 3), buy); // 30k temp coins
		assertEquals(30_000, s.withdrawable(COINS, buy)); // coins can't be bought
		assertEquals(1, s.withdrawable(WHIP, buy)); // 30k buys one 25k whip

		s.applyRealBank(bank(COINS, 1_030_000, WHIP, 1), buy); // took 2 whips
		assertEquals(5_000, s.count(COINS)); // paid for one
		assertEquals(1, s.getOwed().get(WHIP).longValue()); // the other is owed
		assertEquals(0, s.spent); // no debt
	}

	@Test
	public void startingTradeablesAreOwedUntilDeposited()
	{
		RebuildSession s = RebuildSession.start(0);
		assertEquals(1001, s.oweCarried(bank(WHIP, 1, COINS, 1000), STRICT));
		s.applyRealBank(bank(), STRICT);
		assertEquals(1, s.getOwed().get(WHIP).longValue()); // shown as owed straight away
		assertEquals(0, s.violations); // starting items aren't a rule break

		s.applyRealBank(bank(WHIP, 1, COINS, 1500), STRICT); // deposit them + 500 earned coins
		assertTrue(s.getOwed().isEmpty()); // paid back
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
	public void startingUntradeablesAreLockedButNewOnesAreYours()
	{
		RebuildSession.Policy paid = policy(UntradeableMode.CUSTOM, 1_000_000, false, 0);
		RebuildSession s = RebuildSession.start(0);
		assertEquals(2, s.oweCarried(bank(TORSO, 1, WHIP, 1), paid)); // wearing a torso and a whip at Start
		s.applyRealBank(bank(FIRE_CAPE, 1), paid);
		assertEquals(1, s.getOwed().get(TORSO).longValue()); // both show as owed until deposited
		assertEquals(1, s.getOwed().get(WHIP).longValue());

		List<RebuildSession.Change> c = s.applyRealBank(bank(FIRE_CAPE, 2, TORSO, 1, WHIP, 1), paid); // bank them + a newly earned fire cape
		assertEquals(0, s.count(TORSO)); // untradeable you started with: not yours...
		assertEquals(1, s.poolCount(TORSO)); // ...it's locked, to unlock under your settings
		assertEquals(1, s.count(FIRE_CAPE)); // the fire cape you earned: yours
		assertEquals(1, s.poolCount(FIRE_CAPE)); // the one from your bank is still locked
		assertEquals(0, s.count(WHIP)); // tradeable you started with: paid back
		assertTrue(s.getOwed().isEmpty());
		assertEquals(1, c.stream().filter(x -> x.itemId == TORSO).findFirst().get().locked);
	}

	@Test
	public void moonclanBoughtDuringTheRunIsYours()
	{
		// your example: a moonclan hat costs 1m to unlock, but a few thousand on Lunar Isle
		RebuildSession.Policy custom = policy(UntradeableMode.CUSTOM, 1_000_000, false, 0);
		RebuildSession s = RebuildSession.start(0);
		s.oweCarried(bank(MOONCLAN_HAT, 1), custom); // wearing one at Start
		s.applyRealBank(bank(MOONCLAN_HAT, 4), custom); // 4 more in the real bank

		s.applyRealBank(bank(MOONCLAN_HAT, 7), custom); // deposit the starting one + 2 bought on Lunar Isle
		assertEquals(2, s.count(MOONCLAN_HAT)); // the 2 bought ones are yours
		assertEquals(5, s.poolCount(MOONCLAN_HAT)); // the starting one joins the 4 locked ones
		assertEquals(1_000_000, s.unlockFee(MOONCLAN_HAT, custom)); // unlocking those still costs 1m
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
		s.applyRealBank(bank(WHIP, 1, COINS, 25_000), buy);
		assertEquals(1, s.withdrawable(WHIP, buy));
		s.applyRealBank(bank(COINS, 25_000), buy); // buy the whip
		assertEquals(0, s.count(COINS));
		assertEquals(25_000, s.paid);
		s.applyRealBank(bank(COINS, 25_000, WHIP, 1), buy); // deposit it: now yours
		assertEquals(1, s.count(WHIP));
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
	public void customCostsKeepCommasInsideNumbers()
	{
		// the reported bug: "1,000,000" used to become 1 gp
		Map<String, Long> custom = PerduCosts.parseCustom("Fire cape: 1,000,000, Infernal cape: 5,500,000\nVoid knight top: 750,000");
		assertEquals(Long.valueOf(1_000_000), custom.get("fire cape"));
		assertEquals(Long.valueOf(5_500_000), custom.get("infernal cape"));
		assertEquals(Long.valueOf(750_000), custom.get("void knight top"));
	}

	@Test
	public void eatenStartingFoodStaysOwedUntilReplaced()
	{
		// start with 20 sharks, eat 3, cook 2 new ones, deposit all 19
		final int SHARK = 385;
		RebuildSession s = RebuildSession.start(0);
		s.oweCarried(bank(SHARK, 20), STRICT);
		s.applyRealBank(bank(), STRICT);

		List<RebuildSession.Change> c = s.applyRealBank(bank(SHARK, 19), STRICT);
		assertEquals(19, c.get(0).repaid); // all go towards the 20 owed
		assertEquals(0, s.count(SHARK));
		assertEquals(1, s.getOwed().get(SHARK).longValue()); // still owe 1

		s.applyRealBank(bank(SHARK, 23), STRICT); // 4 more new sharks
		assertTrue(s.getOwed().isEmpty()); // the first one pays off the debt
		assertEquals(3, s.count(SHARK)); // the other 3 count
	}

	@Test
	public void spendingStartingCoinsDoesNotBuyProgress()
	{
		// the loophole: carry 10m, spend it on a whip outside the bank, deposit the whip
		RebuildSession s = RebuildSession.start(0);
		s.oweCarried(bank(COINS, 10_000_000), STRICT);
		s.applyRealBank(bank(), STRICT);
		s.applyRealBank(bank(WHIP, 1), STRICT); // deposit the whip bought with real-bank coins
		assertEquals(1, s.count(WHIP)); // the whip is in the temp bank...
		assertEquals(10_000_000, s.getOwed().get(COINS).longValue()); // ...but the 10m is still owed
		assertEquals(10_000_000, s.owedValue(id -> id == COINS ? 1 : 0));
		// and the whip doesn't count as progress while the 10m is owed
		assertEquals(0, s.netValue(id -> id == COINS ? 1 : id == WHIP ? 2_000_000 : 0));

		s.applyRealBank(bank(WHIP, 1, COINS, 10_000_000), STRICT); // pay the 10m back
		assertEquals(2_000_000, s.netValue(id -> id == COINS ? 1 : id == WHIP ? 2_000_000 : 0));
	}

	@Test
	public void withdrawOptionAmounts()
	{
		assertEquals(22_000_000, RebuildSession.requestedAmount("Withdraw-All", 22_000_000));
		assertEquals(21_999_999, RebuildSession.requestedAmount("Withdraw-All-but-1", 22_000_000));
		assertEquals(10, RebuildSession.requestedAmount("Withdraw-10", 22_000_000));
		assertEquals(500, RebuildSession.requestedAmount("Withdraw-500", 22_000_000)); // last-X amount
		assertEquals(-1, RebuildSession.requestedAmount("Withdraw-X", 22_000_000)); // prompt: unknown
	}

	@Test
	public void withdrawBlockingRule()
	{
		// temp bank has 30k coins, real stack is 22m
		assertTrue(RebuildSession.overdraws(22_000_000, 22_000_000, 30_000)); // Withdraw-All: blocked
		assertTrue(RebuildSession.overdraws(21_999_999, 22_000_000, 30_000)); // All-but-1: blocked
		assertFalse(RebuildSession.overdraws(10, 22_000_000, 30_000)); // Withdraw-10: fine
		assertFalse(RebuildSession.overdraws(30_000, 22_000_000, 30_000)); // exactly what you have: fine
		assertFalse(RebuildSession.overdraws(-1, 22_000_000, 30_000)); // Withdraw-X with something to take: allowed
		assertTrue(RebuildSession.overdraws(-1, 22_000_000, 0)); // Withdraw-X with nothing in the temp bank: blocked
		assertFalse(RebuildSession.overdraws(1_000, 500, 600)); // asking for more than the real bank has
	}

	@Test
	public void canUnlockNowChecksOneItem()
	{
		RebuildSession.Policy free = policy(UntradeableMode.ALLOWED, 0, false, 0);
		RebuildSession s = RebuildSession.start(0);
		s.applyRealBank(bank(FIRE_CAPE, 1), free);
		assertTrue(s.canUnlockNow(FIRE_CAPE, free));
		assertFalse(s.canUnlockNow(TORSO, free)); // not in the pool
		assertFalse(s.canUnlockNow(FIRE_CAPE, STRICT)); // unlocking turned off
		s.unlock(FIRE_CAPE, free);
		assertFalse(s.canUnlockNow(FIRE_CAPE, free)); // already unlocked
	}

	@Test
	public void lootingBagIsAlwaysYours()
	{
		final int LOOTING_BAG = 11941;
		RebuildSession.Policy bagPolicy = new RebuildSession.Policy()
		{
			public boolean alwaysYours(int id)
			{
				return id == LOOTING_BAG;
			}

			public boolean isUntradeable(int id)
			{
				return id == LOOTING_BAG || UNTRADEABLE.contains(id);
			}

			public boolean isUnlockable(int id)
			{
				return false; // like "Combat gear": a looting bag would never be offered
			}

			public UntradeableMode untradeableMode()
			{
				return UntradeableMode.REPAIR_COST;
			}

			public long untradeableCost(int id)
			{
				return 1_000_000;
			}

			public boolean buyTradeables()
			{
				return false;
			}

			public long tradeablePrice(int id)
			{
				return 0;
			}
		};

		// one in the bank at Start: it's yours, not locked away
		RebuildSession inBank = RebuildSession.start(0);
		inBank.applyRealBank(bank(LOOTING_BAG, 1, FIRE_CAPE, 1), bagPolicy);
		assertEquals(1, inBank.count(LOOTING_BAG));
		assertEquals(0, inBank.poolCount(LOOTING_BAG));
		assertEquals(1, inBank.poolCount(FIRE_CAPE)); // other untradeables still locked
		assertEquals(1, inBank.withdrawable(LOOTING_BAG, bagPolicy)); // you can take it out

		// carried at Start: not owed or locked, and depositing it adds it to the temp bank
		RebuildSession carried = RebuildSession.start(0);
		assertEquals(0, carried.oweCarried(bank(LOOTING_BAG, 1), bagPolicy));
		carried.applyRealBank(bank(), bagPolicy);
		assertTrue(carried.getOwed().isEmpty());
		carried.applyRealBank(bank(LOOTING_BAG, 1), bagPolicy);
		assertEquals(1, carried.count(LOOTING_BAG));
	}

	@Test
	public void stackNumbersLikeTheGame()
	{
		assertEquals("5", Amounts.stackFormat(5));
		assertEquals("99999", Amounts.stackFormat(99_999));
		assertEquals("100K", Amounts.stackFormat(100_000));
		assertEquals("9999K", Amounts.stackFormat(9_999_999));
		assertEquals("22M", Amounts.stackFormat(22_772_817));
	}

	@Test
	public void perduTableLoads()
	{
		assertEquals(150_000, PerduCosts.cost("Fire cape"));
		assertEquals(600_000, PerduCosts.cost("avernic defender"));
		assertEquals(-1, PerduCosts.cost("Coins"));
	}
}
