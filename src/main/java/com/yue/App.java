package com.yue;

public class App {
    public static void main(String[] args) throws Exception {
        // parse router id from arguments
        int id = args.length == 0 ? 1 : Integer.parseInt(args[0]);
        Router router = new Router(id);
        router.init();
        router.run();
    }
}
