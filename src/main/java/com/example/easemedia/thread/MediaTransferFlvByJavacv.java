package com.example.easemedia.thread;


import com.example.easemedia.entity.dto.CameraDto;
import com.example.easemedia.service.MediaService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber.Exception;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * <b>支持转复用或转码线程<b> <b> 什么情况下会转复用?</b>
 * <p>
 * 视频源的音视频编码必须是浏览器和flv规范两者同时支持的编码，比如H264/AAC，
 * </p>
 * <p>
 * 否则将进行转码。
 * </p>
 * <p>
 * 转封装暂不支持hevc、vvc、vp8、vp9、g711、g771a等编码
 * </p>
 * <b> 转码累积延迟补偿暂未实现。</b> *
 * 由于转流过程中的拉流解码和编码是个线性串联链，多线程转码也不能解决该问题，后面可能需要采用主动跳包方式来解决
 */
@Slf4j
public class MediaTransferFlvByJavacv extends MediaTransfer{

	private boolean grabberStatus = false;

	private boolean recorderStatus = false;

	private FFmpegFrameGrabber grabber;// 拉流器
	private FFmpegFrameRecorder recorder;// 推流录制器

	/**
	 * true:转复用,false:转码
	 */
	boolean transferFlag = false;// 默认转码


	/**
	 * @param cameraDto 流对象
	 */
	public MediaTransferFlvByJavacv(CameraDto cameraDto) {
		super();
		this.cameraDto = cameraDto;
	}

	public boolean isRunning() {
		return running;
	}

	public void setRunning(boolean running) {
		this.running = running;
	}

	public boolean isGrabberStatus() {
		return grabberStatus;
	}

	public void setGrabberStatus(boolean grabberStatus) {
		this.grabberStatus = grabberStatus;
	}

	public boolean isRecorderStatus() {
		return recorderStatus;
	}

	public void setRecorderStatus(boolean recorderStatus) {
		this.recorderStatus = recorderStatus;
	}

	/**
	 * 创建拉流器
	 * 
	 * @return boolean
	 */
	protected boolean createGrabber() {
		// 拉流器
		grabber = new FFmpegFrameGrabber(cameraDto.getUrl());
		// 超时时间(15秒)
		grabber.setOption("stimeout", cameraDto.getNetTimeout());
		grabber.setOption("threads", "1");
		// grabber.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
		// 设置缓存大小，提高画质、减少卡顿花屏
		grabber.setOption("buffer_size", "1024000");

		// 读写超时，适用于所有协议的通用读写超时
		grabber.setOption("rw_timeout", cameraDto.getReadOrWriteTimeout());
		// 探测视频流信息，为空默认5000000微秒
		grabber.setOption("probesize", cameraDto.getReadOrWriteTimeout());
		// 解析视频流信息，为空默认5000000微秒
		grabber.setOption("analyzeduration", cameraDto.getReadOrWriteTimeout());
		
		//针对某些协议或数据有问题的流，一起动就关闭，可以尝试下面参数
//		grabber.setAudioStream(1);

		// 如果为rtsp流，增加配置
		if ("rtsp".equals(cameraDto.getUrl().substring(0, 4))) {
			// 设置打开协议tcp / udp
			grabber.setOption("rtsp_transport", "tcp");
			// 首选TCP进行RTP传输
			grabber.setOption("rtsp_flags", "prefer_tcp");

		} else if ("rtmp".equals(cameraDto.getUrl().substring(0, 4))) {
			// rtmp拉流缓冲区，默认3000毫秒
			grabber.setOption("rtmp_buffer", "1000");
			// 默认rtmp流为直播模式，不允许seek
			// grabber.setOption("rtmp_live", "live");

		} else if ("desktop".equals(cameraDto.getUrl())) {
			// 支持本地屏幕采集，可以用于监控屏幕、局域网和wifi投屏等
			grabber.setFormat("gdigrab");
			grabber.setOption("draw_mouse", "1");// 绘制鼠标
			grabber.setNumBuffers(0);
			grabber.setOption("fflags", "nobuffer");
			grabber.setOption("framerate", "25");
			grabber.setFrameRate(25);
		}

		try {
			grabber.start();
			log.info("\r\n{}\r\n启动拉流器成功", cameraDto.getUrl());
			return grabberStatus = true;
		} catch (Exception e) {
			MediaService.cameras.remove(cameraDto.getMediaKey());
			log.error("\r\n{}\r\n启动拉流器失败，网络超时或视频源不可用", cameraDto.getUrl());
//			e.printStackTrace();
		}
		return grabberStatus = false;
	}

	/**
	 * 创建转码推流录制器
	 * 
	 * @return
	 */
	protected boolean createTransterOrRecodeRecorder() {
		recorder = new FFmpegFrameRecorder(bos, grabber.getImageWidth(), grabber.getImageHeight(),
				grabber.getAudioChannels());
		recorder.setFormat("flv");
		if (!transferFlag) {
			// 转码
			recorder.setInterleaved(false);
			recorder.setVideoOption("tune", "zerolatency");
			recorder.setVideoOption("preset", "ultrafast");
			recorder.setVideoOption("crf", "26");
			recorder.setVideoOption("threads", "1");
			recorder.setFrameRate(25);// 设置帧率
			recorder.setGopSize(25);// 设置gop,与帧率相同，相当于间隔1秒chan's一个关键帧
//			recorder.setVideoBitrate(500 * 1000);// 码率500kb/s
//			recorder.setVideoCodecName("libx264");	//javacv 1.5.5无法使用libx264名称，请使用下面方法
			recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
			recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
			recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
//			recorder.setAudioCodecName("aac");
			recorder.setOption("keyint_min", "25");	//gop最小间隔
			
			/**
			 * 启用RDOQ算法，优化视频质量 1：在视频码率和视频质量之间取得平衡 2：最大程度优化视频质量（会降低编码速度和提高码率）
			 */
			recorder.setTrellis(1);
			recorder.setMaxDelay(0);// 设置延迟
			try {
				recorder.start();
				return recorderStatus = true;
			} catch (org.bytedeco.javacv.FrameRecorder.Exception e1) {
				log.info("启动转码录制器失败", e1);
				MediaService.cameras.remove(cameraDto.getMediaKey());
				e1.printStackTrace();
			}
		} else {
			// 转复用
			// 不让recorder关联关闭outputStream
			recorder.setCloseOutputStream(false);
			try {
				recorder.start(grabber.getFormatContext());
				return recorderStatus = true;
			} catch (org.bytedeco.javacv.FrameRecorder.Exception e) {
				log.warn("\r\n{}\r\n启动转复用录制器失败", cameraDto.getUrl());
				// 如果转复用失败，则自动切换到转码模式
				transferFlag = false;
				if (recorder != null) {
					try {
						recorder.stop();
					} catch (org.bytedeco.javacv.FrameRecorder.Exception e1) {
					}
				}
				if (createTransterOrRecodeRecorder()) {
					log.error("\r\n{}\r\n切换到转码模式", cameraDto.getUrl());
					return true;
				}
				log.error("\r\n{}\r\n切换转码模式失败", cameraDto.getUrl());
				e.printStackTrace();
			}
		}
		return recorderStatus = false;
	}

	/**
	 * 是否支持flv的音视频编码
	 * 
	 * @return
	 */
	private boolean supportFlvFormatCodec() {
		int audioChannels = grabber.getAudioChannels();
		int vcodec = grabber.getVideoCodec();
		int acodec = grabber.getAudioCodec();
		return (cameraDto.getType() == 0)
				&& ("desktop".equals(cameraDto.getUrl()) || avcodec.AV_CODEC_ID_H264 == vcodec
						|| avcodec.AV_CODEC_ID_H263 == vcodec)
				&& (audioChannels == 0 || avcodec.AV_CODEC_ID_AAC == acodec || avcodec.AV_CODEC_ID_AAC_LATM == acodec);
	}


	/**
	 * 执行推流
	 *
	 * @return
	 */
	@SneakyThrows
	public void execute() {
		CompletableFuture.runAsync(() -> {
			if (!createGrabber()) {
				return;
			}
			transferFlag = supportFlvFormatCodec();
			if (!createTransterOrRecodeRecorder()) {
				return;
			}

			try {
				grabber.flush();
			} catch (Exception e) {
				log.info("清空拉流器缓存失败", e);
				e.printStackTrace();
			}
			if (header == null) {
				header = bos.toByteArray();
//				System.out.println(HexUtil.encodeHexStr(header));
				bos.reset();
			}

			running = true;

//		super.transferCallback.start(true);
			// 启动监听线程（用于判断是否需要自动关闭推流）
			listenClient();

			// 时间戳计算
			long startTime = 0;
			long videoTS = 0;
			// 累积延迟计算
//		long latencyDifference = 0;// 累积延迟
//		long lastLatencyDifference = 0;// 当前最新一组gop的延迟
//		long maxLatencyThreshold = 30000000;// 最大延迟阈值，如果lastLatencyDifference-latencyDifference>maxLatencyThreshold，则重启拉流器
//		long processTime = 0;// 上一帧处理耗时，用于延迟时间补偿，处理耗时不算进累积延迟

			for (; running && grabberStatus && recorderStatus;) {

				try {
					if (transferFlag) {
						// 转复用
						long startGrab = System.currentTimeMillis();
						AVPacket pkt = grabber.grabPacket();
						if ((System.currentTimeMillis() - startGrab) > 5000) {
//						doReConnect();
//						continue;
							log.info("\r\n{}\r\n视频流网络异常>>>", cameraDto.getUrl());
							closeMedia();
							break;
						}
						if (null != pkt && !pkt.isNull()) {
							if (startTime == 0) {
								startTime = System.currentTimeMillis();
							}
							videoTS = 1000 * (System.currentTimeMillis() - startTime);
							// 判断时间偏移
							if (videoTS > recorder.getTimestamp()) {
								// System.out.println("矫正时间戳: " + videoTS + " : " + recorder.getTimestamp() + "
								// -> "
								// + (videoTS - recorder.getTimestamp()));
								recorder.setTimestamp((videoTS));
							}
							recorder.recordPacket(pkt);
						}
					} else {
						// 转码
						long startGrab = System.currentTimeMillis();
						Frame frame = grabber.grab(); // 这边判断相机断网，正常50左右，断线15000
						if ((System.currentTimeMillis() - startGrab) > 5000) {
//						doReConnect();
//						continue;
							log.info("\r\n{}\r\n视频流网络异常>>>", cameraDto.getUrl());
							closeMedia();
							break;
						}

						if (frame != null) {
							if (startTime == 0) {
								startTime = System.currentTimeMillis();
							}
							videoTS = 1000 * (System.currentTimeMillis() - startTime);
							// 判断时间偏移
							if (videoTS > recorder.getTimestamp()) {
								// System.out.println("矫正时间戳: " + videoTS + " : " + recorder.getTimestamp() + "
								// -> "
								// + (videoTS - recorder.getTimestamp()));
								recorder.setTimestamp((videoTS));
							}
							recorder.record(frame);
						}
					}
				} catch (Exception e) {
					grabberStatus = false;
					MediaService.cameras.remove(cameraDto.getMediaKey());
				} catch (org.bytedeco.javacv.FrameRecorder.Exception e) {
					recorderStatus = false;
					MediaService.cameras.remove(cameraDto.getMediaKey());
				}

				if (bos.size() > 0) {
					byte[] b = bos.toByteArray();
					bos.reset();

					// 发送视频到前端
					log.info("javacv: " + Arrays.toString(b));
					sendFrameData(b);
				}
			}

			// 启动失败，直接关闭， close包含stop和release方法。录制文件必须保证最后执行stop()方法
			try {
				recorder.close();
				grabber.close();
				bos.close();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				closeMedia();
			}
			log.info("关闭媒体流-javacv，{} ", cameraDto.getUrl());
		});
	}



	/**
	 * 重连，目前重连有些问题，停止后时间戳也变化了，发现相机连不上先直接断开，清除缓存，后续再优化
	 */
//	private void doReConnect() {
//		try {
//			boolean createGrabber = createGrabber();
//			while (!createGrabber) {
//				try {
//					TimeUnit.MILLISECONDS.sleep(500);
//				} catch (InterruptedException e) {
//				}
//				log.info("\r\n{}\r\n尝试重连>>>", camera.getUrl());
//				createGrabber = createGrabber();
//			}
//			
//		} finally {
//			try {
//				grabber.flush();
//			} catch (Exception e) {
//				running = false;
//				MediaService.cameras.remove(camera.getMediaKey());
//			}
//		}
//		log.info("\r\n{}\r\n重连成功", camera.getUrl());
//	}

	/**
	 * 关闭流媒体
	 */
	private void closeMedia() {
		running = false;
		MediaService.cameras.remove(cameraDto.getMediaKey());

		// 媒体异常时，主动断开前端长连接
		stopFFmpeg();
	}
}
