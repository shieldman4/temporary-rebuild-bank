package com.rebuildbank;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Perdu repair / Lost Property shop prices, keyed by lower-case item name. */
@Slf4j
final class PerduCosts
{
	private static final Map<String, Long> COSTS = load();

	private PerduCosts()
	{
	}

	/** Cost for an item name, or -1 if Perdu doesn't handle it. */
	static long cost(String itemName)
	{
		return itemName == null ? -1 : COSTS.getOrDefault(itemName.toLowerCase(Locale.ROOT), -1L);
	}

	private static Map<String, Long> load()
	{
		Map<String, Long> map = new HashMap<>();
		try (InputStream in = PerduCosts.class.getResourceAsStream("perdu_costs.txt"))
		{
			if (in == null)
			{
				return Collections.emptyMap();
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; )
			{
				line = line.trim();
				int eq = line.lastIndexOf('=');
				if (line.isEmpty() || line.startsWith("#") || eq < 0)
				{
					continue;
				}
				map.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), Long.parseLong(line.substring(eq + 1).trim()));
			}
		}
		catch (IOException | NumberFormatException e)
		{
			log.warn("Couldn't load Perdu costs", e);
		}
		return map;
	}

	/**
	 * Parses "Fire cape: 1m" / "Fire cape=1m" entries, one per line or separated by ";" or ",".
	 * A comma followed by a digit is part of a number ("1,000,000"), not a separator.
	 */
	static Map<String, Long> parseCustom(String text)
	{
		Map<String, Long> map = new HashMap<>();
		if (text == null)
		{
			return map;
		}
		for (String part : text.split("[\n;]|,(?!\\d)"))
		{
			int sep = Math.max(part.lastIndexOf(':'), part.lastIndexOf('='));
			if (sep <= 0)
			{
				continue;
			}
			long v = Amounts.parse(part.substring(sep + 1));
			String name = part.substring(0, sep).trim().toLowerCase(Locale.ROOT);
			if (v >= 0 && !name.isEmpty())
			{
				map.put(name, v);
			}
		}
		return map;
	}
}
