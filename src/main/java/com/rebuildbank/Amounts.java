package com.rebuildbank;

import java.text.NumberFormat;
import java.util.Locale;

final class Amounts
{
	private Amounts()
	{
	}

	/** Parses "10m", "2.5b", "500k", "1,000,000". Returns -1 if invalid. */
	static long parse(String text)
	{
		if (text == null)
		{
			return -1;
		}
		String s = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
		if (s.isEmpty())
		{
			return -1;
		}
		double mult = 1;
		char last = s.charAt(s.length() - 1);
		if (last == 'k' || last == 'm' || last == 'b')
		{
			mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
			s = s.substring(0, s.length() - 1);
		}
		try
		{
			double v = Double.parseDouble(s) * mult;
			return v < 0 || v > Long.MAX_VALUE ? -1 : (long) v;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	static String format(long v)
	{
		return NumberFormat.getIntegerInstance(Locale.US).format(v);
	}

	/** 1.2M style, for compact labels. */
	static String shortFormat(long v)
	{
		long a = Math.abs(v);
		if (a >= 1_000_000_000L)
		{
			return String.format(Locale.US, "%.2fB", v / 1e9);
		}
		if (a >= 1_000_000L)
		{
			return String.format(Locale.US, "%.2fM", v / 1e6);
		}
		if (a >= 10_000L)
		{
			return String.format(Locale.US, "%.1fK", v / 1e3);
		}
		return format(v);
	}
}
