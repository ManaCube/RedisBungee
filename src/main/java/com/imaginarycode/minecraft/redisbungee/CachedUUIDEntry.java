package com.imaginarycode.minecraft.redisbungee;

import java.util.Calendar;
import java.util.UUID;

/*
 * @author cullan on 1/2/2023
 */
public class CachedUUIDEntry
{

	private final String   name;
	private final UUID     uuid;
	private final Calendar expiry;

	private CachedUUIDEntry(String name, UUID uuid, Calendar expiry) {
		this.name = name;
		this.uuid = uuid;
		this.expiry = expiry;
	}

	public boolean expired() {
		return Calendar.getInstance().after(expiry);
	}

}
