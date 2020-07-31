package com.yue;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * DebugHelper
 *
 * @author: Wenduo Yue
 * @date: 7/21/20
 */
class DebugHelper {
    public enum Level {
        NONE, INFO, DEBUG
    }

    private static Level debugLevel = Level.DEBUG;

    static Level getDebugLevel() {
        return debugLevel;
    }

    private static String header = "";

    static void setHeader(String s) {
        header = s;
    }

    static void Log(Level level, String s) {
        if (debugLevel.compareTo(level) >= 0)
            System.out.println(new SimpleDateFormat("HH:mm:ss>> ").format(new Date()) + header + " " + s);
    }
}
