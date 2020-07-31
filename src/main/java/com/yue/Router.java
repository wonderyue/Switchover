package com.yue;

/**
 * Router
 *
 * @author: Wenduo Yue
 * @date: 7/21/20
 */

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.util.concurrent.ScheduledFuture;

class Router {

    Config.RouterConfig routerConfig;
    private int id;
    private Config config;
    // receiver: receives messages from adjacent routers
    private ServerBootstrap bootstrap;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel tcpChannel;
    // sender: sends messages to adjacent routers
    private Bootstrap[] senderBootstraps;
    private NioEventLoopGroup[] senderGroups;
    private Map<Integer, Channel> senderChannels; // key: routerId

    private Map<Integer, Integer> routingTable; // <dst, nextHop>
    private Map<String, Integer> ip2routerId; // <ip, routerId>
    private Map<Integer, Map<Integer, Set<Integer>>> multicastRoutingTable; // <groupId, <rootId, <neighborId>>
    private ScheduledFuture future;

    private Set<Integer> subscribedGroupIds; // <groupId>
    // <groupId, <rootId, <sourceId, <neighborId>>
    private Map<Integer, Map<Integer, Map<Integer, Set<Integer>>>> pruneTable;
    private Set<Integer> pruneGroupIds;

    Router(int id) {
        this.id = id;
    }

    void init() throws FileNotFoundException, UnknownHostException {
        // parse config file
        Gson gson = new Gson();
        config = gson.fromJson(new JsonReader(new FileReader("config.json")), Config.class);
        routerConfig = config.routers.get(id);
        DebugHelper.setHeader(String.format("Router %d(%s)", routerConfig.id, routerConfig.name));
        // initialize tcp server channel
        // assign tasks to workers
        bossGroup = new NioEventLoopGroup();
        // handle connections
        workerGroup = new NioEventLoopGroup();
        // server bootstrap
        bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
            .childHandler(new ServerInitializer(this));
        // initialize tcp client channel
        senderBootstraps = new Bootstrap[routerConfig.neighbors.length];
        senderGroups = new NioEventLoopGroup[routerConfig.neighbors.length];
        for (int i = 0; i < routerConfig.neighbors.length; i++) {
            senderGroups[i] = new NioEventLoopGroup();
            senderBootstraps[i] = new Bootstrap();
            senderBootstraps[i].group(senderGroups[i]).channel(NioSocketChannel.class).handler(new ClientInitializer());
        }
        // initialize address map and routing table
        ip2routerId = new HashMap<>();
        for (Map.Entry<Integer, Config.RouterConfig> kv : config.routers.entrySet()) {
            InetAddress address = InetAddress.getByName(kv.getValue().ip);
            ip2routerId.put(address.getHostAddress(), kv.getKey());
        }
        routingTable = routerConfig.routingTable;
        multicastRoutingTable = new HashMap<>();
        subscribedGroupIds = new HashSet<>();
        pruneTable = new HashMap<>();
        pruneGroupIds = new HashSet<>();
        DebugHelper.Log(DebugHelper.Level.INFO, "start");
    }

    void run() throws InterruptedException {
        DebugHelper.Log(DebugHelper.Level.INFO, "running");
        // bind tcp channel
        // server channel
        tcpChannel = bootstrap.bind(routerConfig.port).sync().channel();
        // client channel
        senderChannels = new HashMap<>();
        // waiting for connections with all the neighbors are established
        while (senderChannels.size() < routerConfig.neighbors.length)
            for (int i = 0; i < routerConfig.neighbors.length; i++) {
                int neighborId = routerConfig.neighbors[i];
                if (senderChannels.containsKey(neighborId))
                    continue;
                Config.RouterConfig neighbor = config.routers.get(neighborId);
                try {
                    senderChannels.put(neighborId,
                        senderBootstraps[i].connect(neighbor.ip, neighbor.port).sync().channel());
                    DebugHelper.Log(DebugHelper.Level.INFO, "success: " + neighbor.ip);
                } catch (Exception e) {
                    DebugHelper.Log(DebugHelper.Level.INFO, "fail: " + neighbor.ip);
                }
                Thread.sleep(100);
            }
        AtomicInteger seconds = new AtomicInteger();
        future = bossGroup.scheduleAtFixedRate(() -> {
            try {
                if (routerConfig.actions != null && routerConfig.actions.containsKey(seconds.get()))
                    executeAction(routerConfig.actions.get(seconds.get()));
            } catch (Exception e) {
                e.printStackTrace();
            }
            seconds.getAndIncrement();
        }, 0, config.regular_timer, TimeUnit.SECONDS);
        // auto shutdown in config.shutdown_timer seconds
        if (config.shutdown_timer > 0)
            bossGroup.schedule(() -> {
                closeChannel();
            }, config.shutdown_timer, TimeUnit.SECONDS);
        DebugHelper.Log(DebugHelper.Level.INFO, "initialized");
        // block for channel closing
        tcpChannel.closeFuture().sync();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        for (Channel channel : senderChannels.values())
            channel.closeFuture().sync();
        for (int i = 0; i < routerConfig.neighbors.length; i++)
            senderGroups[i].shutdownGracefully();
    }

    private void closeChannel() {
        if (future != null && tcpChannel != null) {
            future.cancel(true);
            tcpChannel.close();
            DebugHelper.Log(DebugHelper.Level.INFO, "Close channel.");
        }
    }

    private void executeAction(Config.Action action) {
        switch (action.type) {
            case JOIN:
                join(action.groupId, action.groupId);
                break;
            case SWITCHOVER:
                switchover(Integer.parseInt(action.content), action.groupId);
                break;
            case MULTICAST:
                multicast(action.groupId, action.content);
                break;
            default:
                break;
        }
    }

    private int getNextHop(int to) {
        return routingTable.get(to);
    }

    private void unicast(int to, Msg.Packet packet) {
        to = getNextHop(to);
        if (DebugHelper.getDebugLevel().compareTo(DebugHelper.Level.DEBUG) >= 0)
            packet = packet.toBuilder().setDebugInfo(packet.getDebugInfo() + routerConfig.name + "->").build();
        DebugHelper.Log(DebugHelper.Level.INFO, "Send " + packet.getType() + " to " + config.routers.get(to).name);
        senderChannels.get(to).writeAndFlush(packet).addListener(channelFuture -> {
            if (channelFuture.isSuccess())
                DebugHelper.Log(DebugHelper.Level.INFO, "succeeded");
            else
                DebugHelper.Log(DebugHelper.Level.INFO, "failed");
        });
    }

    private void multicast(int groupId, String content) {
        // tunnel to RP
        // if groupId is pruned, it means all the subscriber have leave the group-shared tree
        // so there is no need to send message to RP
        if (!pruneGroupIds.contains(groupId))
            unicast(groupId, Msg.Packet.newBuilder().setType(Msg.Packet.Type.MULTICAST_TUNNELING).setSrcId(id)
                .setDstId(groupId).setContent(content).putExtra("groupId", String.valueOf(groupId)).build());
        // flood in source-based tree
        multicastRoutingTable.putIfAbsent(groupId, new HashMap<>());
        Map<Integer, Set<Integer>> map = multicastRoutingTable.get(groupId);
        if (!map.containsKey((id)))
            return;
        Msg.Packet packet = Msg.Packet.newBuilder().setType(Msg.Packet.Type.MULTICAST_FLOODING).setSrcId(id)
            .setContent(content).putExtra("groupId", String.valueOf(groupId)).build();
        flood(groupId, packet, routerConfig.ip);
    }

    private void flood(int groupId, Msg.Packet packet, String srcIp) {
        multicastRoutingTable.putIfAbsent(groupId, new HashMap<>());
        Map<Integer, Set<Integer>> map = multicastRoutingTable.get(groupId);
        // priority: source-based tree > group-shared tree
        int srcId = packet.getSrcId();
        Set<Integer> set = map.containsKey(srcId) ? map.get(srcId) : map.get(groupId);
        if (set == null)
            return;
        for (int to : set) {
            if (to == ip2routerId.getOrDefault(srcIp, -1))
                continue; // do not flood backward
            unicast(to, packet);
        }
    }

    private void join(int to, int groupId) {
        subscribedGroupIds.add(groupId);
        unicast(to, Msg.Packet.newBuilder().setType(Msg.Packet.Type.JOIN).setSrcId(id).setDstId(to)
            .putExtra("groupId", String.valueOf(groupId)).build());
    }

    // join source-based tree and prune group-shared tree
    private void switchover(int to, int groupId) {
        join(to, groupId);
        unicast(groupId, Msg.Packet.newBuilder().setType(Msg.Packet.Type.PRUNE).setSrcId(id).setDstId(groupId)
            .putExtra("groupId", String.valueOf(groupId)).putExtra("sourceId", String.valueOf(to)).build());
    }

    private void leave(int to, int groupId) {
        subscribedGroupIds.remove(groupId);
        unicast(to, Msg.Packet.newBuilder().setType(Msg.Packet.Type.LEAVE).setSrcId(id).setDstId(to)
            .putExtra("groupId", String.valueOf(groupId)).build());
    }

    void handleJoinMsg(String srcIp, Msg.Packet packet) {
        int groupId = Integer.parseInt(packet.getExtraMap().get("groupId"));
        multicastRoutingTable.putIfAbsent(groupId, new HashMap<>());
        Map<Integer, Set<Integer>> map = multicastRoutingTable.get(groupId);
        boolean isInTheTree = map.containsKey(packet.getDstId());
        map.putIfAbsent(packet.getDstId(), new HashSet<>());
        Set<Integer> set = map.get(packet.getDstId());
        set.add(ip2routerId.get(srcIp));
        // if not in the tree and not source or RP, then graft onto the tree
        if (!isInTheTree && packet.getDstId() != id)
            unicast(packet.getDstId(), packet);
    }

    void handleLeaveMsg(String srcIp, Msg.Packet packet) {
        int groupId = Integer.parseInt(packet.getExtraMap().get("groupId"));
        multicastRoutingTable.putIfAbsent(groupId, new HashMap<>());
        Map<Integer, Set<Integer>> map = multicastRoutingTable.get(groupId);
        boolean isInTheTree = map.containsKey(packet.getDstId());
        if (!isInTheTree)
            return;
        Set<Integer> set = map.get(packet.getDstId());
        set.remove(ip2routerId.get(srcIp));
        if (set.size() == 0)
            if (packet.getDstId() != id)// if current router is not destination, send LEAVE message to next hop
                unicast(packet.getDstId(), packet);
    }

    void handlePruneMsg(String srcIp, Msg.Packet packet) {
        int groupId = Integer.parseInt(packet.getExtraMap().get("groupId"));
        int sourceId = Integer.parseInt(packet.getExtraMap().get("sourceId"));
        pruneTable.putIfAbsent(groupId, new HashMap<>());
        multicastRoutingTable.putIfAbsent(groupId, new HashMap<>());
        Map<Integer, Set<Integer>> map = multicastRoutingTable.get(groupId);
        boolean isInTheTree = map.containsKey(packet.getDstId());
        if (!isInTheTree)
            return;
        Set<Integer> set = map.get(packet.getDstId());
        Map<Integer, Map<Integer, Set<Integer>>> groups = pruneTable.get(groupId);
        groups.putIfAbsent(packet.getDstId(), new HashMap<>());
        Map<Integer, Set<Integer>> roots = groups.get(packet.getDstId());
        roots.putIfAbsent(sourceId, new HashSet<>());
        Set<Integer> sources = roots.get(sourceId);
        sources.add(ip2routerId.get(srcIp));
        if (set.size() == sources.size())
            if (packet.getDstId() != id)// if current router is not destination, send PRUNE message to next hop
                unicast(packet.getDstId(), packet);
            else
                unicast(sourceId, Msg.Packet.newBuilder().setType(Msg.Packet.Type.REVERSE_TUNNELING).setSrcId(id)
                    .setDstId(sourceId).putExtra("groupId", String.valueOf(groupId)).build());
    }

    // keep flooding msg to neighbors in the tree, if current router is a subscriber, unpack the massage
    void handleFloodMsg(String srcIp, Msg.Packet packet) {
        int groupId = Integer.parseInt(packet.getExtraMap().get("groupId"));
        if (subscribedGroupIds.contains(groupId))
            unpackMulticastMsg(packet);
        flood(groupId, packet, srcIp);
    }

    // if current router is RP, multicast msg, or pass the packet to RP
    void handleTunnelMsg(String srcIp, Msg.Packet packet) {
        if (packet.getDstId() == id) // flood
            flood(id, packet.toBuilder().setType(Msg.Packet.Type.MULTICAST_FLOODING).build(), "");
        else // transit
            unicast(packet.getDstId(), packet);
    }

    // record prune groupId or keep forwarding
    void handleReverseTunnelMsg(Msg.Packet packet) {
        if (packet.getDstId() == id)
            pruneGroupIds.add(packet.getSrcId());
        else
            unicast(packet.getDstId(), packet);
    }

    private void unpackMulticastMsg(Msg.Packet packet) {
        DebugHelper.Log(DebugHelper.Level.INFO,
            String.format("Unpack multicast message form %s: %s", packet.getSrcId(), packet.getContent()));
    }

}

class ServerInitializer extends ChannelInitializer {
    private Router router;

    ServerInitializer(Router router) {
        this.router = router;
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new ProtobufVarint32FrameDecoder());
        pipeline.addLast(new ProtobufDecoder(Msg.Packet.getDefaultInstance()));
        pipeline.addLast(new ProtobufVarint32LengthFieldPrepender());
        pipeline.addLast(new ProtobufEncoder());
        pipeline.addLast(new RouterHandler(router));
    }
}

class ClientInitializer extends ChannelInitializer {

    @Override
    protected void initChannel(Channel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new ProtobufVarint32FrameDecoder());
        pipeline.addLast(new ProtobufDecoder(Msg.Packet.getDefaultInstance()));
        pipeline.addLast(new ProtobufVarint32LengthFieldPrepender());
        pipeline.addLast(new ProtobufEncoder());
        pipeline.addLast(new SimpleChannelInboundHandler<Msg.Packet>() {
            @Override
            protected void channelRead0(ChannelHandlerContext channelHandlerContext, Msg.Packet packet)
                throws Exception {
                DebugHelper.Log(DebugHelper.Level.INFO, packet.getType() + " from " + packet.getSrcId());
            }
        });
    }
}