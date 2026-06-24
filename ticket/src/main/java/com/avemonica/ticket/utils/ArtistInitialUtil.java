package com.avemonica.ticket.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import org.springframework.util.StringUtils;

public class ArtistInitialUtil {

    private ArtistInitialUtil() {
    }

    /**
     * 艺人首字母规则：
     * 1. 英文开头：返回 A-Z
     * 2. 中文开头：返回首个汉字拼音首字母 A-Z
     * 3. 数字、符号、日文假名、其他字符：返回 #
     */
    public static String resolveFirstLetter(String name) {
        if (!StringUtils.hasText(name)) {
            return "#";
        }

        String text = name.trim();
        if (text.isEmpty()) {
            return "#";
        }

        char firstChar = text.charAt(0);

        // 英文
        if (firstChar >= 'A' && firstChar <= 'Z') {
            return String.valueOf(firstChar);
        }
        if (firstChar >= 'a' && firstChar <= 'z') {
            return String.valueOf((char) (firstChar - 32));
        }

        // 中文
        if (isChinese(firstChar)) {
            String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(firstChar);
            if (pinyinArray != null && pinyinArray.length > 0 && pinyinArray[0].length() > 0) {
                char firstLetter = Character.toUpperCase(pinyinArray[0].charAt(0));
                if (firstLetter >= 'A' && firstLetter <= 'Z') {
                    return String.valueOf(firstLetter);
                }
            }
        }

        // 符号、数字、日文假名、其他非 A-Z 开头
        return "#";
    }

    private static boolean isChinese(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}