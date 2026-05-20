package com.avemonica.ticket.utils;

import java.security.SecureRandom;

public class RandomUtil {
    private static final String CHAR_POOL = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generateAmUsername() {
        StringBuilder sb = new StringBuilder("am_");
        for (int i = 0; i < 10; i++) {
            sb.append(CHAR_POOL.charAt(RANDOM.nextInt(CHAR_POOL.length())));
        }
        return sb.toString();
    }
}