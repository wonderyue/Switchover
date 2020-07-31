package com.yue;

/**
 * Config
 *
 * @author: Wenduo Yue
 * @date: 7/21/20
 */
import java.util.Map;

class Config {
    class RouterConfig {
        int id;
        String ip;
        int port;
        String name;
        int[] neighbors;
        Map<Integer, Integer> routingTable;
        Map<Integer, Action> actions;
    }

    public enum ACTION_TYPE {
        JOIN, SWITCHOVER, MULTICAST
    }

    class Action {
        ACTION_TYPE type;
        Integer groupId;
        String content;
    }

    int regular_timer = 30;
    int time_out_timer = 180;
    int gc_timer = 120;
    int shutdown_timer = 600;
    Map<Integer, RouterConfig> routers;
}
