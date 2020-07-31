package com.yue;

/**
 * RouterHandler
 *
 * @author: Wenduo Yue
 * @date: 7/21/20
 */

import java.net.InetSocketAddress;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class RouterHandler extends SimpleChannelInboundHandler<Msg.Packet> {

    private Router router;

    RouterHandler(Router router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Msg.Packet msg) {
        String srcIp = ((InetSocketAddress)ctx.channel().remoteAddress()).getAddress().getHostAddress();
        DebugHelper.Log(DebugHelper.Level.INFO,
            "Receive " + msg.getType() + ": " + msg.getDebugInfo() + router.routerConfig.name);
        if (msg.getType() == Msg.Packet.Type.JOIN)
            router.handleJoinMsg(srcIp, msg);
        else if (msg.getType() == Msg.Packet.Type.LEAVE)
            router.handleLeaveMsg(srcIp, msg);
        else if (msg.getType() == Msg.Packet.Type.MULTICAST_TUNNELING)
            router.handleTunnelMsg(srcIp, msg);
        else if (msg.getType() == Msg.Packet.Type.MULTICAST_FLOODING)
            router.handleFloodMsg(srcIp, msg);
        else if (msg.getType() == Msg.Packet.Type.PRUNE)
            router.handlePruneMsg(srcIp, msg);
        else if (msg.getType() == Msg.Packet.Type.REVERSE_TUNNELING)
            router.handleReverseTunnelMsg(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
