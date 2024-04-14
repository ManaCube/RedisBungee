package com.imaginarycode.minecraft.redisbungee.velocity.util;

import java.io.InputStream;

/*
 * @author cullan on 4/6/2024
 */
public class VelocityUtil {

    public static InputStream getResourceAsStream(Class clazz, String name) {
        return clazz.getClassLoader().getResourceAsStream(name);
    }

}
