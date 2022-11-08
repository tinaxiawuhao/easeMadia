package com.example.easemedia.thread;

import cn.hutool.core.collection.CollUtil;
import com.example.easemedia.common.ClientType;
import com.example.easemedia.common.MediaConstant;
import com.example.easemedia.entity.dto.CameraDto;
import com.example.easemedia.service.MediaService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FrameGrabber.Exception;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 使用ffmpeg推拉流，可以说无敌了
 * <p>
 * 优点：支持各种杂七杂八的流，兼容性比较好，稳定，不容易出错，自身带有重连机制，可以自己使用命令封装 缺点：系统会存在多个ffmpeg进程,
 * 无法直接操作帧，延迟优化没javacv方便
 */
@Slf4j
public class MediaTransferFlvByFFmpeg extends MediaTransfer {


    private CameraDto cameraDto;
    //命令存储
    private final List<String> command = new ArrayList<>();
    //socket通道
    private ServerSocket tcpServer = null;

    //一个新进程
    private Process process;
    private boolean running = false; // 启动
    private boolean enableLog = true;

    // 记录当前
    long currentTimeMillis = System.currentTimeMillis();

    public MediaTransferFlvByFFmpeg(final String executable) {
        command.add(executable);
        buildCommand();
    }

    public MediaTransferFlvByFFmpeg(CameraDto cameraDto) {
        command.add(System.getProperty(MediaConstant.ffmpegPathKey));
        this.cameraDto = cameraDto;
        buildCommand();
    }

    public MediaTransferFlvByFFmpeg(final String executable, CameraDto cameraDto) {
        command.add(executable);
        this.cameraDto = cameraDto;
        buildCommand();
    }

    public MediaTransferFlvByFFmpeg(final String executable, CameraDto cameraDto, boolean enableLog) {
        command.add(executable);
        this.cameraDto = cameraDto;
        this.enableLog = enableLog;
        buildCommand();
    }

    public boolean isEnableLog() {
        return enableLog;
    }

    public void setEnableLog(boolean enableLog) {
        this.enableLog = enableLog;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    private MediaTransferFlvByFFmpeg addArgument(String argument) {
        command.add(argument);
        return this;
    }

    /**
     * 构建ffmpeg转码命令,新版javacv移除libx264，使用libopenh264 查看显卡硬件加速支持的选项ffmpeg -hwaccels
     * 查看ffmpeg支持选项 linux：ffmpeg -codecs | grep cuvid， window：ffmpeg -codecs |
     * findstr cuvid h264_nvenc ffmpeg -hwaccel cuvid -c:v h264_cuvid
     * -rtsp_transport tcp -i "rtsp地址" -c:v h264_nvenc -b:v 500k -vf
     * scale_npp=1280:-1 -y /home/2.mp4
     * <p>
     * -hwaccel cuvid：指定使用cuvid硬件加速 -c:v h264_cuvid：使用h264_cuvid进行视频解码 -c:v
     * h264_nvenc：使用h264_nvenc进行视频编码 -vf
     * scale_npp=1280:-1：指定输出视频的宽高，注意，这里和软解码时使用的-vf scale=x:x不一样
     * <p>
     * 转码期间nvidia-smi查看显卡状态 -hwaccel_device N 指定某颗GPU执行转码任务
     */
    private void buildCommand() {
        // 如果为rtsp流，增加配置
        if ("rtsp".equals(cameraDto.getUrl().substring(0, 4))) {
            this.addArgument("-rtsp_transport").addArgument("tcp");
        }
        //如果是本地文件
        if (cameraDto.getType() == 1) {
            this.addArgument("-re");
        }

        this.addArgument("-i").addArgument(cameraDto.getUrl())
                .addArgument("-max_delay").addArgument("1")
//		.addArgument("-strict").addArgument("experimental")
                .addArgument("-g").addArgument("25").addArgument("-r").addArgument("25")
//		.addArgument("-b").addArgument("200000")
//		.addArgument("-filter_complex").addArgument("setpts='(RTCTIME - RTCSTART) / (TB * 1000000)'")
                .addArgument("-c:v").addArgument("libopenh264").addArgument("-preset:v").addArgument("ultrafast")
//		.addArgument("-preset:v").addArgument("fast")
                .addArgument("-tune:v").addArgument("zerolatency")
//		.addArgument("-crf").addArgument("26")
                .addArgument("-c:a").addArgument("aac")
//		.addArgument("-qmin").addArgument("28")
//		.addArgument("-qmax").addArgument("32")
//		.addArgument("-b:v").addArgument("448k")
//		.addArgument("-b:a").addArgument("64k")
                .addArgument("-f").addArgument("flv");
    }


    /**
     * 执行推流
     *
     * @return
     */
    @SneakyThrows
    public MediaTransferFlvByFFmpeg execute() {
        command.add(getOutput());
        log.info(CollUtil.join(command, " "));
        process = new ProcessBuilder(command).start();
        running = true;
        listenNetTimeout();
        dealStream(process);
        outputData();
        listenClient();
        return this;
    }

    /**
     * flv数据
     */
    private void outputData() {
        //					ByteArrayOutputStream bos = new ByteArrayOutputStream();
        //							System.out.println(HexUtil.encodeHexStr(header));
        // 帧数据
        // 发送到前端
        //					e1.printStackTrace();
        //					超时关闭
        //					e.printStackTrace();
        Thread outputThread = new Thread(new Runnable() {
            public void run() {
                Socket client = null;
                try {
                    client = tcpServer.accept();
                    DataInputStream input = new DataInputStream(client.getInputStream());

                    byte[] buffer = new byte[1024];
                    int len = 0;
//					ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    while (running) {

                        len = input.read(buffer);
                        if (len == -1) {
                            break;
                        }

                        bos.write(buffer, 0, len);

                        if (header == null) {
                            header = bos.toByteArray();
//							System.out.println(HexUtil.encodeHexStr(header));
                            bos.reset();
                            continue;
                        }

                        // 帧数据
                        byte[] data = bos.toByteArray();
                        bos.reset();

                        // 发送到前端
                        sendFrameData(data);
                    }

                    try {
                        client.close();
                    } catch (java.lang.Exception e) {
                    }
                    try {
                        input.close();
                    } catch (java.lang.Exception e) {
                    }
                    try {
                        bos.close();
                    } catch (java.lang.Exception e) {
                    }

                    log.info("关闭媒体流-ffmpeg，{} ", cameraDto.getUrl());

                } catch (SocketTimeoutException e1) {
//					e1.printStackTrace();
//					超时关闭
                } catch (IOException e) {
//					e.printStackTrace();
                } finally {
                    MediaService.cameras.remove(cameraDto.getMediaKey());
                    running = false;
                    process.destroy();
                    try {
                        if (null != client) {
                            client.close();
                        }
                    } catch (IOException e) {
                    }
                    try {
                        if (null != tcpServer) {
                            tcpServer.close();
                        }
                    } catch (IOException e) {
                    }
                }
            }
        });

        outputThread.start();
    }

    /**
     * 监听客户端
     */
    public void listenClient() {
        Thread listenThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    hasClient();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }
            }
        });
        listenThread.start();
    }

    /**
     * 监听网络异常超时
     */
    public void listenNetTimeout() {
        CompletableFuture.runAsync(() -> {
                    while (true) {
                        if ((System.currentTimeMillis() - currentTimeMillis) > 15000) {
                            log.info("网络异常超时");
                            MediaService.cameras.remove(cameraDto.getMediaKey());
                            stopFFmpeg();
                            break;
                        }

                        try {
                            TimeUnit.MILLISECONDS.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                }
        );
    }

    public static MediaTransferFlvByFFmpeg atPath() {
        return atPath(null);
    }

    public static MediaTransferFlvByFFmpeg atPath(final String absPath) {
        final String executable;
        if (absPath != null) {
            executable = absPath;
        } else {
//			executable = "ffmpeg";
            executable = System.getProperty(MediaConstant.ffmpegPathKey);
        }
        return new MediaTransferFlvByFFmpeg(executable);
    }

    /**
     * 控制台输出
     *
     * @param process
     */
    private void dealStream(Process process) {
        if (process == null) {
            return;
        }
        // 处理InputStream的线程
        CompletableFuture.runAsync(() -> {
                    BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    try {
                        while (running) {
                            line = in.readLine();
                            currentTimeMillis = System.currentTimeMillis();
                            if (line == null) {
                                break;
                            }
                            if (enableLog) {
                                log.info("output: " + line);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        try {
                            running = false;
                            in.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                }
        );
        // 处理ErrorStream的线程
        CompletableFuture.runAsync(() -> {
            BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line = null;
            try {
                while (running) {
                    line = err.readLine();
                    currentTimeMillis = System.currentTimeMillis();
                    if (line == null) {
                        break;
                    }
                    if (enableLog) {
                        log.info("ffmpeg: " + line);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    running = false;
                    err.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 输出到tcp
     *
     * @return tcp地址
     */
    private String getOutput() {
        try {
            tcpServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            tcpServer.setSoTimeout(10000);
            return "tcp://" +
                    tcpServer.getInetAddress().getHostAddress() +
                    ":" +
                    tcpServer.getLocalPort();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("无法启用端口");
        }
    }

    /**
     * 关闭
     */
    public void stopFFmpeg() {
        this.running = false;
        try {
            this.process.destroy();
            log.info("关闭媒体流-ffmpeg，{} ", cameraDto.getUrl());
        } catch (java.lang.Exception e) {
            process.destroyForcibly();
        }

        // 媒体异常时，主动断开前端长连接
        for (Entry<String, ChannelHandlerContext> entry : wsClients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (java.lang.Exception ignored) {
            } finally {
                wsClients.remove(entry.getKey());
            }
        }
        for (Entry<String, ChannelHandlerContext> entry : httpClients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (java.lang.Exception ignored) {
            } finally {
                httpClients.remove(entry.getKey());
            }
        }
    }

    /**
     * 关闭流
     *
     * @return
     */
    public void hasClient() {

        int newHcSize = httpClients.size();
        int newWcSize = wsClients.size();
        if (hcSize != newHcSize || wcSize != newWcSize) {
            hcSize = newHcSize;
            wcSize = newWcSize;
            log.info("\r\n{}\r\nhttp连接数：{}, ws连接数：{} \r\n", cameraDto.getUrl(), newHcSize, newWcSize);
        }

        // 无需自动关闭
        if (!cameraDto.isAutoClose()) {
            return;
        }

        if (httpClients.isEmpty() && wsClients.isEmpty()) {
            // 等待20秒还没有客户端，则关闭推流
            if (noClient > cameraDto.getNoClientsDuration()) {
                running = false;
                MediaService.cameras.remove(cameraDto.getMediaKey());
            } else {
                noClient += 1000;
//				log.info("\r\n{}\r\n {} 秒自动关闭推拉流 \r\n", camera.getUrl(), noClientsDuration-noClient);
            }
        } else {
            // 重置计时
            noClient = 0;
        }
    }

    /**
     * 发送帧数据
     *
     * @param data
     */
    private void sendFrameData(byte[] data) {
        // ws
        for (Entry<String, ChannelHandlerContext> entry : wsClients.entrySet()) {
            try {
                if (entry.getValue().channel().isWritable()) {
                    entry.getValue().writeAndFlush(new BinaryWebSocketFrame(Unpooled.copiedBuffer(data)));
                } else {
                    wsClients.remove(entry.getKey());
                    hasClient();
                }
            } catch (java.lang.Exception e) {
                wsClients.remove(entry.getKey());
                hasClient();
                e.printStackTrace();
            }
        }
        // http
        for (Entry<String, ChannelHandlerContext> entry : httpClients.entrySet()) {
            try {
                if (entry.getValue().channel().isWritable()) {
                    entry.getValue().writeAndFlush(Unpooled.copiedBuffer(data));
                } else {
                    httpClients.remove(entry.getKey());
                    hasClient();
                }
            } catch (java.lang.Exception e) {
                httpClients.remove(entry.getKey());
                hasClient();
                e.printStackTrace();
            }
        }
    }

    /**
     * 新增客户端
     *
     * @param ctx   netty client
     * @param ctype enum,ClientType
     */
    public void addClient(ChannelHandlerContext ctx, ClientType ctype) {
        int timeout = 0;
        while (true) {
            try {
                if (header != null) {
                    try {
                        if (ctx.channel().isWritable()) {
                            // 发送帧前先发送header
                            if (ClientType.HTTP.getType() == ctype.getType()) {
                                ChannelFuture future = ctx.writeAndFlush(Unpooled.copiedBuffer(header));
                                future.addListener(new GenericFutureListener<Future<? super Void>>() {
                                    @Override
                                    public void operationComplete(Future<? super Void> future) throws Exception {
                                        if (future.isSuccess()) {
                                            httpClients.put(ctx.channel().id().toString(), ctx);
                                        }
                                    }
                                });
                            } else if (ClientType.WEBSOCKET.getType() == ctype.getType()) {
                                ChannelFuture future = ctx
                                        .writeAndFlush(new BinaryWebSocketFrame(Unpooled.copiedBuffer(header)));
                                future.addListener(new GenericFutureListener<Future<? super Void>>() {
                                    @Override
                                    public void operationComplete(Future<? super Void> future) throws Exception {
                                        if (future.isSuccess()) {
                                            wsClients.put(ctx.channel().id().toString(), ctx);
                                        }
                                    }
                                });
                            }
                        }

                    } catch (java.lang.Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

                // 等待推拉流启动
                Thread.sleep(50);
                // 启动录制器失败
                timeout += 50;
                if (timeout > 30000) {
                    break;
                }
            } catch (java.lang.Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws Exception {
//		ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
//		System.out.println(serverSocket.getLocalPort());
//		System.out.println(serverSocket.getInetAddress().getHostAddress());

        MediaTransferFlvByFFmpeg.atPath().addArgument("-i")
                .addArgument("rtsp://admin:VZCDOY@192.168.2.84:554/Streaming/Channels/102").addArgument("-g")
                .addArgument("5").addArgument("-c:v").addArgument("libx264").addArgument("-c:a").addArgument("aac")
                .addArgument("-f").addArgument("flv").execute();
    }

}
