package net.sourceforge.opencamera.remotecontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import net.sourceforge.opencamera.AccessControl;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.preview.Preview;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Local Wi-Fi/Hotspot browser remote control. */
public class WebRemoteControl {
    private static final String TAG = "WebRemoteControl";
    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_PORT = 8090;
    private static final int CLIENT_TIMEOUT_MS = 5000;
    private static final int MODE_SWITCH_RETRY_DELAY_MS = 500;
    private static final int MODE_SWITCH_MAX_ATTEMPTS = 12;

    private static volatile int activePort = DEFAULT_PORT;

    private final MainActivity main_activity;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean serverRunning;

    public WebRemoteControl(MainActivity main_activity) {
        this.main_activity = main_activity;
    }

    public synchronized void startRemoteControl() {
        if( !remoteEnabled() || main_activity.isAppPaused() ) {
            stopRemoteControl();
            return;
        }
        if( serverRunning ) {
            return;
        }

        try {
            ServerSocket socket = openServerSocket();
            serverSocket = socket;
            activePort = socket.getLocalPort();
            serverRunning = true;
            serverThread = new Thread(() -> serve(socket), "PhotoFinish-WebRemote");
            serverThread.start();
            if( MyDebug.LOG ) {
                Log.d(TAG, "Web remote listening on port " + activePort);
            }
        }
        catch(IOException e) {
            serverRunning = false;
            serverSocket = null;
            activePort = DEFAULT_PORT;
            MyDebug.logStackTrace(TAG, "failed to start web remote server", e);
        }
    }

    private ServerSocket openServerSocket() throws IOException {
        IOException lastException = null;
        for(int port = DEFAULT_PORT; port <= MAX_PORT; port++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
                return socket;
            }
            catch(IOException e) {
                lastException = e;
            }
        }
        throw lastException != null ? lastException : new IOException("No web remote port available");
    }

    public synchronized void stopRemoteControl() {
        serverRunning = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        activePort = DEFAULT_PORT;
        if( socket != null ) {
            try {
                socket.close();
            }
            catch(IOException e) {
                if( MyDebug.LOG ) {
                    Log.d(TAG, "failed to close web remote socket: " + e.getMessage());
                }
            }
        }
        serverThread = null;
    }

    public boolean remoteEnabled() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.EnableWebRemote, false);
    }

    public boolean remoteRunning() {
        return serverRunning;
    }

    private void serve(ServerSocket socket) {
        while( serverRunning && !socket.isClosed() ) {
            try {
                Socket client = socket.accept();
                handleClient(client);
            }
            catch(SocketException e) {
                if( serverRunning && MyDebug.LOG ) {
                    Log.d(TAG, "web remote socket exception: " + e.getMessage());
                }
            }
            catch(IOException e) {
                if( serverRunning ) {
                    MyDebug.logStackTrace(TAG, "web remote request failed", e);
                }
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        try(Socket socket = client) {
            socket.setSoTimeout(CLIENT_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream outputStream = socket.getOutputStream();

            String requestLine = reader.readLine();
            if( requestLine == null || requestLine.trim().isEmpty() ) {
                return;
            }

            String[] requestParts = requestLine.split(" ");
            if( requestParts.length < 2 ) {
                sendText(outputStream, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request");
                return;
            }

            String method = requestParts[0].toUpperCase(Locale.US);
            String target = requestParts[1];
            while( true ) {
                String header = reader.readLine();
                if( header == null || header.length() == 0 ) {
                    break;
                }
            }

            RequestTarget requestTarget = parseTarget(target);
            routeRequest(outputStream, method, requestTarget.path, requestTarget.queryParams);
        }
    }

    private void routeRequest(OutputStream outputStream, String method, String path, Map<String, String> queryParams) throws IOException {
        if( "OPTIONS".equals(method) ) {
            sendText(outputStream, 204, "No Content", "text/plain; charset=utf-8", "");
            return;
        }

        if( "/".equals(path) || "/index.html".equals(path) ) {
            if( !"GET".equals(method) ) {
                sendJson(outputStream, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            sendText(outputStream, 200, "OK", "text/html; charset=utf-8", buildControlPage());
            return;
        }

        if( !isAuthorized(queryParams) ) {
            sendJson(outputStream, 401, "{\"ok\":false,\"error\":\"invalid_pin\"}");
            return;
        }

        if( "/api/status".equals(path) ) {
            if( !"GET".equals(method) ) {
                sendJson(outputStream, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            sendJson(outputStream, 200, buildStatusJson());
            return;
        }

        if( "/api/preview.jpg".equals(path) ) {
            if( !"GET".equals(method) ) {
                sendJson(outputStream, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            sendPreviewJpeg(outputStream);
            return;
        }

        if( !"POST".equals(method) ) {
            sendJson(outputStream, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }

        CommandResult result;
        switch(path) {
            case "/api/shutter":
                result = runCommandOnUiThread(this::shutter);
                break;
            case "/api/photo":
                result = runCommandOnUiThread(this::takePhoto);
                break;
            case "/api/video/start":
                result = runCommandOnUiThread(this::startVideo);
                break;
            case "/api/video/stop":
                result = runCommandOnUiThread(this::stopVideo);
                break;
            case "/api/mode/toggle":
                result = runCommandOnUiThread(this::togglePhotoVideoMode);
                break;
			case "/api/standby/toggle":
				result = runCommandOnUiThread(this::toggleStandbyMode);
				break;
			case "/api/standby/on":
				result = runCommandOnUiThread(() -> setStandbyMode(true));
				break;
			case "/api/standby/off":
				result = runCommandOnUiThread(() -> setStandbyMode(false));
				break;
			case "/api/screen/dim/toggle":
				result = runCommandOnUiThread(this::toggleScreenDim);
				break;
			case "/api/screen/dim/on":
				result = runCommandOnUiThread(() -> setScreenDim(true));
				break;
			case "/api/screen/dim/off":
				result = runCommandOnUiThread(() -> setScreenDim(false));
				break;
            case "/api/capture-rate":
                result = runCommandOnUiThread(() -> setCaptureRate(queryParams.get("value")));
                break;
            case "/api/iso":
                result = runCommandOnUiThread(() -> setISO(queryParams.get("value")));
                break;
            case "/api/shutter-speed":
                result = runCommandOnUiThread(() -> setShutterSpeed(queryParams.get("value")));
                break;
			default:
				sendJson(outputStream, 404, "{\"ok\":false,\"error\":\"not_found\"}");
				return;
        }

        sendJson(outputStream, result.ok ? 200 : 409, result.toJson());
    }

    private boolean isAuthorized(Map<String, String> queryParams) {
        String pin = queryParams.get("pin");
        return getOrCreatePin(main_activity).equals(pin);
    }

    private CommandResult shutter() {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }
        main_activity.takePicture(false);
        return CommandResult.ok("Shutter requested");
    }

    private CommandResult takePhoto() {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }

        Preview preview = main_activity.getPreview();
        if( preview.isVideoRecording() ) {
            main_activity.takePicture(true);
            return CommandResult.ok("Photo snapshot requested while recording");
        }

        if( preview.isVideo() ) {
            requestVideoMode(false);
            runWhenCameraReady(() -> !main_activity.getPreview().isVideo(), () -> {
                if( !main_activity.getPreview().isVideo() ) {
                    main_activity.takePicture(false);
                }
            });
            return CommandResult.ok("Switching to photo mode and taking photo");
        }

        main_activity.takePicture(false);
        return CommandResult.ok("Photo requested");
    }

    private CommandResult startVideo() {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }

        Preview preview = main_activity.getPreview();
        if( preview.isVideoRecording() ) {
            return CommandResult.ok("Already recording");
        }

        if( !preview.isVideo() ) {
            requestVideoMode(true);
            runWhenCameraReady(() -> main_activity.getPreview().isVideo(), () -> {
                Preview delayedPreview = main_activity.getPreview();
                if( delayedPreview.isVideo() && !delayedPreview.isVideoRecording() ) {
                    main_activity.takePicture(false);
                }
            });
            return CommandResult.ok("Switching to video mode and starting recording");
        }

        main_activity.takePicture(false);
        return CommandResult.ok("Start recording requested");
    }

    private CommandResult stopVideo() {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }

        Preview preview = main_activity.getPreview();
        if( !preview.isVideoRecording() ) {
            return CommandResult.ok("Not recording");
        }

        main_activity.takePicture(false);
        return CommandResult.ok("Stop recording requested");
    }

    private CommandResult togglePhotoVideoMode() {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }

        if( main_activity.getPreview().isVideoRecording() ) {
            return CommandResult.error("recording_active", "Stop recording before switching modes");
        }

        requestVideoMode(!main_activity.getPreview().isVideo());
        return CommandResult.ok("Photo/video mode switch requested");
    }

	private CommandResult toggleStandbyMode() {
		return setStandbyMode(!main_activity.isStandbyModeActive());
	}

	private CommandResult setStandbyMode(boolean enabled) {
		if( main_activity.isAppPaused() || main_activity.isCameraInBackground() || main_activity.getPreview() == null ) {
			return CommandResult.error("camera_unavailable", "Open the camera screen on the phone");
		}

		main_activity.setStandbyModeActive(enabled);
		return CommandResult.ok(main_activity.isStandbyModeActive() ? "Stand-by mode active" : "Stand-by mode off");
	}

	private CommandResult toggleScreenDim() {
		return setScreenDim(!main_activity.isRemoteScreenDimActive());
	}

	private CommandResult setScreenDim(boolean enabled) {
		if( main_activity.isStandbyModeActive() ) {
			return CommandResult.error("standby_active", "Stand-by mode already dims the screen");
		}
		if( main_activity.isAppPaused() || main_activity.isCameraInBackground() || main_activity.getPreview() == null ) {
			return CommandResult.error("camera_unavailable", "Open the camera screen on the phone");
		}

		main_activity.setRemoteScreenDim(enabled);
		return CommandResult.ok(main_activity.isRemoteScreenDimActive() ? "Screen dimmed" : "Brightness restored");
	}

    private CommandResult setCaptureRate(String value) {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }
        Preview preview = main_activity.getPreview();
        if( preview.isVideoRecording() ) {
            return CommandResult.error("recording_active", "Stop recording before changing recording speed");
        }

        float captureRateFactor;
        try {
            captureRateFactor = Float.parseFloat(value);
        }
        catch(NumberFormatException | NullPointerException e) {
            return CommandResult.error("bad_value", "Invalid recording speed");
        }

        float selectedRate = -1.0f;
        for(float supportedRate : main_activity.getApplicationInterface().getSupportedVideoCaptureRates()) {
            if( supportedRate <= 1.0f + 1.0e-5f && Math.abs(supportedRate - captureRateFactor) < 1.0e-5f ) {
                selectedRate = supportedRate;
                break;
            }
        }
        if( selectedRate < 0.0f ) {
            return CommandResult.error("unsupported", "Recording speed is not supported on this camera");
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putFloat(PreferenceKeys.getVideoCaptureRatePreferenceKey(preview.getCameraId(), main_activity.getApplicationInterface().getCameraIdSPhysicalPref()), selectedRate);
        editor.apply();
        main_activity.updateForSettings(true, "", false, false);
        return CommandResult.ok("Recording speed: " + captureRateLabel(selectedRate));
    }

    private CommandResult setISO(String value) {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }
        Preview preview = main_activity.getPreview();
        if( preview.isVideoRecording() ) {
            return CommandResult.error("recording_active", "Stop recording before changing ISO");
        }
        if( value == null ) {
            return CommandResult.error("bad_value", "Invalid ISO");
        }
        if( value.equals("auto") ) {
            main_activity.getApplicationInterface().setISOPref(CameraController.ISO_DEFAULT);
            main_activity.getApplicationInterface().setExposureTimePref(CameraController.EXPOSURE_TIME_DEFAULT);
            main_activity.updateForSettings(true, "", false, true);
            return CommandResult.ok("ISO: Auto");
        }
        if( !supportsRemoteManualExposure(preview) ) {
            return manualExposureUnavailableResult();
        }

        int iso;
        try {
            iso = Integer.parseInt(value);
        }
        catch(NumberFormatException e) {
            return CommandResult.error("bad_value", "Invalid ISO");
        }

        iso = clamp(iso, preview.getMinimumISO(), preview.getMaximumISO());
        preview.setISO(iso);
        return CommandResult.ok(preview.getISOString(iso));
    }

    private CommandResult setShutterSpeed(String value) {
        if( !canOperateCamera() ) {
            return cameraUnavailableResult();
        }
        Preview preview = main_activity.getPreview();
        if( preview.isVideoRecording() ) {
            return CommandResult.error("recording_active", "Stop recording before changing shutter speed");
        }
        if( value == null ) {
            return CommandResult.error("bad_value", "Invalid shutter speed");
        }
        if( value.equals("auto") ) {
            main_activity.getApplicationInterface().setISOPref(CameraController.ISO_DEFAULT);
            main_activity.getApplicationInterface().setExposureTimePref(CameraController.EXPOSURE_TIME_DEFAULT);
            main_activity.updateForSettings(true, "", false, true);
            return CommandResult.ok("Exposure: Auto");
        }
        if( !supportsRemoteManualExposure(preview) || !preview.supportsExposureTime() ) {
            return manualExposureUnavailableResult();
        }

        long exposureTime;
        try {
            exposureTime = Long.parseLong(value);
        }
        catch(NumberFormatException e) {
            return CommandResult.error("bad_value", "Invalid shutter speed");
        }

        if( CameraController.ISO_DEFAULT.equals(main_activity.getApplicationInterface().getISOPref()) ) {
            preview.setISO(getFallbackManualISO(preview));
        }
        exposureTime = clamp(exposureTime, preview.getMinimumExposureTime(), preview.getMaximumExposureTime());
        preview.setExposureTime(exposureTime);
        return CommandResult.ok("Shutter speed:" + preview.getExposureTimeString(exposureTime));
    }

    private boolean supportsRemoteManualExposure(Preview preview) {
        return AccessControl.hasSubscriptionAccess(main_activity) && preview != null && preview.supportsISORange();
    }

    private CommandResult manualExposureUnavailableResult() {
        return CommandResult.error("unsupported", "Manual ISO/shutter speed is not available on this camera or subscription");
    }

    private int getFallbackManualISO(Preview preview) {
        int iso = preview.getCameraController() != null ? preview.getCameraController().getISO() : 0;
        if( iso <= 0 ) {
            iso = 400;
        }
        return clamp(iso, preview.getMinimumISO(), preview.getMaximumISO());
    }

    private boolean canControlCamera() {
        return !main_activity.isAppPaused() && !main_activity.isCameraInBackground() && main_activity.getPreview() != null;
    }

    private boolean canOperateCamera() {
        return canControlCamera() && !main_activity.isStandbyModeActive();
    }

    private CommandResult cameraUnavailableResult() {
        if( main_activity.isStandbyModeActive() ) {
            return CommandResult.error("standby_active", "Exit stand-by mode first");
        }
        return CommandResult.error("camera_unavailable", "Open the camera screen on the phone");
    }

    private void requestVideoMode(boolean videoMode) {
        if( main_activity.getPreview().isVideo() == videoMode ) {
            return;
        }
        main_activity.getApplicationInterface().setVideoPref(videoMode);
        main_activity.updateForSettings(true, "", false, true);
    }

    private void runWhenCameraReady(ReadyCondition readyCondition, Runnable action) {
        Handler handler = new Handler(Looper.getMainLooper());
        retryWhenCameraReady(handler, readyCondition, action, 0);
    }

    private void retryWhenCameraReady(Handler handler, ReadyCondition readyCondition, Runnable action, int attempt) {
        if( main_activity.isAppPaused() ) {
            return;
        }
        if( canOperateCamera() && readyCondition.isReady() ) {
            action.run();
            return;
        }
        if( attempt >= MODE_SWITCH_MAX_ATTEMPTS ) {
            return;
        }
        handler.postDelayed(() -> retryWhenCameraReady(handler, readyCondition, action, attempt + 1), MODE_SWITCH_RETRY_DELAY_MS);
    }

    private CommandResult runCommandOnUiThread(UiCommand command) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CommandResult> result = new AtomicReference<>();
        main_activity.runOnUiThread(() -> {
            try {
                result.set(command.run());
            }
            catch(RuntimeException e) {
                MyDebug.logStackTrace(TAG, "web remote command failed", e);
                result.set(CommandResult.error("command_failed", e.getMessage()));
            }
            finally {
                latch.countDown();
            }
        });

        try {
            if( !latch.await(3, TimeUnit.SECONDS) ) {
                return CommandResult.error("timeout", "Camera command timed out");
            }
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandResult.error("interrupted", "Camera command interrupted");
        }

        CommandResult commandResult = result.get();
        if( commandResult == null ) {
            return CommandResult.error("command_failed", "Camera command failed");
        }
        return commandResult;
    }

    private String buildStatusJson() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        main_activity.runOnUiThread(() -> {
            Preview preview = main_activity.getPreview();
            boolean videoMode = preview != null && preview.isVideo();
            boolean recording = preview != null && preview.isVideoRecording();
            boolean paused = preview != null && preview.isVideoRecordingPaused();
            boolean standbyMode = main_activity.isStandbyModeActive();
            boolean screenDimmed = main_activity.isRemoteScreenDimActive();
            String mode = videoMode ? "video" : "photo";
            result.set("{\"ok\":true"
                    + ",\"mode\":\"" + mode + "\""
                    + ",\"videoMode\":" + videoMode
                    + ",\"recording\":" + recording
                    + ",\"recordingPaused\":" + paused
                    + ",\"standbyMode\":" + standbyMode
                    + ",\"screenDimmed\":" + screenDimmed
                    + ",\"cameraActive\":" + canControlCamera()
                    + ",\"controls\":" + buildControlsJson(preview)
                    + "}");
            latch.countDown();
        });

        try {
            if( !latch.await(3, TimeUnit.SECONDS) ) {
                return "{\"ok\":false,\"error\":\"timeout\"}";
            }
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"interrupted\"}";
        }

        String json = result.get();
        return json != null ? json : "{\"ok\":false,\"error\":\"status_failed\"}";
    }

    private String buildControlsJson(Preview preview) {
        StringBuilder builder = new StringBuilder();
        builder.append("{");

        float captureRate = 1.0f;
        try {
            captureRate = main_activity.getApplicationInterface().getVideoCaptureRateFactor();
        }
        catch(RuntimeException e) {
            if( MyDebug.LOG ) {
                Log.d(TAG, "failed to read capture rate: " + e.getMessage());
            }
        }
        builder.append("\"captureRate\":").append(formatFloat(captureRate));
        builder.append(",\"captureRates\":[");
        boolean firstRate = true;
        if( preview != null ) {
            for(float rate : main_activity.getApplicationInterface().getSupportedVideoCaptureRates()) {
                if( rate > 1.0f + 1.0e-5f ) {
                    continue;
                }
                if( !firstRate ) {
                    builder.append(",");
                }
                builder.append("{\"value\":").append(formatFloat(rate))
                        .append(",\"label\":\"").append(jsonEscape(captureRateLabel(rate))).append("\"}");
                firstRate = false;
            }
        }
        builder.append("]");

        boolean manualExposureSupported = supportsRemoteManualExposure(preview);
        boolean exposureTimeSupported = manualExposureSupported && preview.supportsExposureTime();
        builder.append(",\"manualExposureSupported\":").append(manualExposureSupported);
        builder.append(",\"exposureTimeSupported\":").append(exposureTimeSupported);
        builder.append(",\"iso\":\"").append(jsonEscape(currentIsoValue(preview))).append("\"");
        builder.append(",\"exposureTimeNs\":").append(currentExposureTime(preview));
        builder.append(",\"exposureTimeLabel\":\"").append(jsonEscape(currentExposureTimeLabel(preview))).append("\"");
        builder.append(",\"isoPresets\":").append(buildIsoPresetsJson(preview, manualExposureSupported));
        builder.append(",\"shutterPresets\":").append(buildShutterPresetsJson(preview, exposureTimeSupported));
        builder.append("}");
        return builder.toString();
    }

    private String currentIsoValue(Preview preview) {
        if( preview == null || CameraController.ISO_DEFAULT.equals(main_activity.getApplicationInterface().getISOPref()) ) {
            return "auto";
        }
        if( preview.getCameraController() == null ) {
            return main_activity.getApplicationInterface().getISOPref();
        }
        return String.valueOf(preview.getCameraController().getISO());
    }

    private long currentExposureTime(Preview preview) {
        if( preview == null || CameraController.ISO_DEFAULT.equals(main_activity.getApplicationInterface().getISOPref()) || preview.getCameraController() == null ) {
            return CameraController.EXPOSURE_TIME_DEFAULT;
        }
        return preview.getCameraController().getExposureTime();
    }

    private String currentExposureTimeLabel(Preview preview) {
        long exposureTime = currentExposureTime(preview);
        if( exposureTime == CameraController.EXPOSURE_TIME_DEFAULT || preview == null ) {
            return "Auto";
        }
        return preview.getExposureTimeString(exposureTime).trim();
    }

    private String buildIsoPresetsJson(Preview preview, boolean manualExposureSupported) {
        StringBuilder builder = new StringBuilder();
        builder.append("[\"auto\"");
        if( manualExposureSupported ) {
            int[] isoPresets = {50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 6400};
            for(int iso : isoPresets) {
                if( iso >= preview.getMinimumISO() && iso <= preview.getMaximumISO() ) {
                    builder.append(",\"").append(iso).append("\"");
                }
            }
        }
        builder.append("]");
        return builder.toString();
    }

    private String buildShutterPresetsJson(Preview preview, boolean exposureTimeSupported) {
        StringBuilder builder = new StringBuilder();
        builder.append("[{\"value\":\"auto\",\"label\":\"Auto\"}");
        if( exposureTimeSupported ) {
            long[] exposureTimes = {
                    1000000000L / 30,
                    1000000000L / 60,
                    1000000000L / 120,
                    1000000000L / 240,
                    1000000000L / 500,
                    1000000000L / 1000,
                    1000000000L / 2000,
                    1000000000L / 4000,
                    1000000000L / 8000
            };
            for(long exposureTime : exposureTimes) {
                if( exposureTime >= preview.getMinimumExposureTime() && exposureTime <= preview.getMaximumExposureTime() ) {
                    builder.append(",{\"value\":\"").append(exposureTime)
                            .append("\",\"label\":\"").append(jsonEscape(preview.getExposureTimeString(exposureTime).trim())).append("\"}");
                }
            }
        }
        builder.append("]");
        return builder.toString();
    }

    private static String captureRateLabel(float captureRateFactor) {
        if( Math.abs(captureRateFactor - 1.0f) < 1.0e-5f ) {
            return "Normal";
        }
        if( Math.abs(captureRateFactor - 0.5f) < 1.0e-5f ) {
            return "1/2x";
        }
        if( Math.abs(captureRateFactor - 0.25f) < 1.0e-5f ) {
            return "1/4x";
        }
        if( Math.abs(captureRateFactor - 0.125f) < 1.0e-5f ) {
            return "1/8x";
        }
        return formatFloat(captureRateFactor) + "x";
    }

    private static RequestTarget parseTarget(String target) {
        String path = target;
        String query = "";
        int queryIndex = target.indexOf('?');
        if( queryIndex >= 0 ) {
            path = target.substring(0, queryIndex);
            query = target.substring(queryIndex + 1);
        }
        if( path.isEmpty() ) {
            path = "/";
        }
        return new RequestTarget(path, parseQuery(query));
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if( query == null || query.isEmpty() ) {
            return params;
        }
        String[] pairs = query.split("&");
        for(String pair : pairs) {
            if( pair.isEmpty() ) {
                continue;
            }
            int equalsIndex = pair.indexOf('=');
            String key = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            String value = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
            try {
                params.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            }
            catch(IllegalArgumentException | IOException e) {
                if( MyDebug.LOG ) {
                    Log.d(TAG, "failed to decode query param: " + e.getMessage());
                }
            }
        }
        return params;
    }

    private void sendJson(OutputStream outputStream, int statusCode, String body) throws IOException {
        sendText(outputStream, statusCode, reasonPhrase(statusCode), "application/json; charset=utf-8", body);
    }

    private void sendPreviewJpeg(OutputStream outputStream) throws IOException {
        if( !canOperateCamera() ) {
            sendJson(outputStream, 409, cameraUnavailableResult().toJson());
            return;
        }

        CaptureResult captureResult = capturePreviewJpeg();
        if( captureResult.jpeg == null ) {
            sendJson(outputStream, 409, CommandResult.error("preview_unavailable", captureResult.error != null ? captureResult.error : "Preview is not available").toJson());
            return;
        }

        sendBytes(outputStream, 200, "OK", "image/jpeg", captureResult.jpeg);
    }

    private CaptureResult capturePreviewJpeg() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> bitmapReference = new AtomicReference<>();
        AtomicReference<String> errorReference = new AtomicReference<>();
        main_activity.runOnUiThread(() -> {
            try {
                Preview preview = main_activity.getPreview();
                if( !canOperateCamera() || preview == null ) {
                    errorReference.set("Open the camera screen on the phone");
                    latch.countDown();
                    return;
                }

                View previewView = preview.getView();
                int viewWidth = previewView.getWidth();
                int viewHeight = previewView.getHeight();
                if( viewWidth <= 0 || viewHeight <= 0 ) {
                    errorReference.set("Preview is not ready yet");
                    latch.countDown();
                    return;
                }

                int[] targetSize = scaledPreviewSize(viewWidth, viewHeight);
                if( previewView instanceof TextureView ) {
                    int rotationDegrees = preview.getDisplayRotationDegrees(false);
                    int rawWidth = targetSize[0];
                    int rawHeight = targetSize[1];
                    if( rotationDegrees == 90 || rotationDegrees == 270 ) {
                        rawWidth = targetSize[1];
                        rawHeight = targetSize[0];
                    }
                    Bitmap rawBitmap = Bitmap.createBitmap(rawWidth, rawHeight, Bitmap.Config.ARGB_8888);
                    ((TextureView)previewView).getBitmap(rawBitmap);
                    Bitmap bitmap = rotateBitmap(rawBitmap, -rotationDegrees);
                    if( bitmap != rawBitmap ) {
                        rawBitmap.recycle();
                    }
                    drawGridOverlay(bitmap);
                    bitmapReference.set(bitmap);
                    latch.countDown();
                }
                else if( previewView instanceof SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
                    Bitmap bitmap = Bitmap.createBitmap(targetSize[0], targetSize[1], Bitmap.Config.ARGB_8888);
                    PixelCopy.request((SurfaceView)previewView, bitmap, copyResult -> {
                        if( copyResult == PixelCopy.SUCCESS ) {
                            drawGridOverlay(bitmap);
                            bitmapReference.set(bitmap);
                        }
                        else {
                            bitmap.recycle();
                            errorReference.set("Preview copy failed");
                        }
                        latch.countDown();
                    }, new Handler(Looper.getMainLooper()));
                }
                else {
                    errorReference.set("Preview requires Camera2 or Android 8+");
                    latch.countDown();
                }
            }
            catch(RuntimeException e) {
                MyDebug.logStackTrace(TAG, "failed to capture web preview", e);
                errorReference.set("Preview capture failed");
                latch.countDown();
            }
        });

        try {
            if( !latch.await(3, TimeUnit.SECONDS) ) {
                return new CaptureResult(null, "Preview timed out");
            }
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CaptureResult(null, "Preview interrupted");
        }

        Bitmap bitmap = bitmapReference.get();
        if( bitmap == null ) {
            return new CaptureResult(null, errorReference.get());
        }

        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 55, byteArrayOutputStream);
            return new CaptureResult(byteArrayOutputStream.toByteArray(), null);
        }
        finally {
            bitmap.recycle();
        }
    }

    private static int[] scaledPreviewSize(int viewWidth, int viewHeight) {
        final int maxDimension = 640;
        int targetWidth = viewWidth;
        int targetHeight = viewHeight;
        int largestDimension = Math.max(viewWidth, viewHeight);
        if( largestDimension > maxDimension ) {
            targetWidth = Math.max(1, Math.round(viewWidth * (maxDimension / (float)largestDimension)));
            targetHeight = Math.max(1, Math.round(viewHeight * (maxDimension / (float)largestDimension)));
        }
        return new int[]{targetWidth, targetHeight};
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
        if( normalizedRotation == 0 ) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
        }
        catch(IllegalArgumentException e) {
            return bitmap;
        }
    }

    private void drawGridOverlay(Bitmap bitmap) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        String gridPref = sharedPreferences.getString(PreferenceKeys.ShowGridPreferenceKey, "preference_grid_none");
        if( gridPref == null || "preference_grid_none".equals(gridPref) ) {
            return;
        }

        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float scale = Math.max(1.0f, Math.max(bitmap.getWidth(), bitmap.getHeight()) / 640.0f);
        float strokeWidth = Math.max(1.0f, 2.0f * scale);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        switch(gridPref) {
            case "preference_grid_3x3":
                paint.setColor(Color.WHITE);
                drawLine(canvas, paint, width / 3.0f, 0.0f, width / 3.0f, height - 1.0f);
                drawLine(canvas, paint, 2.0f * width / 3.0f, 0.0f, 2.0f * width / 3.0f, height - 1.0f);
                drawLine(canvas, paint, 0.0f, height / 3.0f, width - 1.0f, height / 3.0f);
                drawLine(canvas, paint, 0.0f, 2.0f * height / 3.0f, width - 1.0f, 2.0f * height / 3.0f);
                break;
            case "preference_grid_phi_3x3":
                paint.setColor(Color.WHITE);
                drawLine(canvas, paint, width / 2.618f, 0.0f, width / 2.618f, height - 1.0f);
                drawLine(canvas, paint, 1.618f * width / 2.618f, 0.0f, 1.618f * width / 2.618f, height - 1.0f);
                drawLine(canvas, paint, 0.0f, height / 2.618f, width - 1.0f, height / 2.618f);
                drawLine(canvas, paint, 0.0f, 1.618f * height / 2.618f, width - 1.0f, 1.618f * height / 2.618f);
                break;
            case "preference_grid_4x2":
                paint.setColor(Color.GRAY);
                drawLine(canvas, paint, width / 4.0f, 0.0f, width / 4.0f, height - 1.0f);
                drawLine(canvas, paint, width / 2.0f, 0.0f, width / 2.0f, height - 1.0f);
                drawLine(canvas, paint, 3.0f * width / 4.0f, 0.0f, 3.0f * width / 4.0f, height - 1.0f);
                drawLine(canvas, paint, 0.0f, height / 2.0f, width - 1.0f, height / 2.0f);
                paint.setColor(Color.WHITE);
                float crosshairsRadius = 20.0f * scale;
                drawLine(canvas, paint, width / 2.0f, height / 2.0f - crosshairsRadius, width / 2.0f, height / 2.0f + crosshairsRadius);
                drawLine(canvas, paint, width / 2.0f - crosshairsRadius, height / 2.0f, width / 2.0f + crosshairsRadius, height / 2.0f);
                break;
            case "preference_grid_crosshair":
                paint.setColor(Color.WHITE);
                drawLine(canvas, paint, width / 2.0f, 0.0f, width / 2.0f, height - 1.0f);
                drawLine(canvas, paint, 0.0f, height / 2.0f, width - 1.0f, height / 2.0f);
                break;
            case "WhiteThin":
                drawCenterLine(canvas, paint, Color.WHITE, 45, strokeWidth);
                break;
            case "WhiteMedium":
                drawCenterLine(canvas, paint, Color.WHITE, 100, 6.0f * scale);
                break;
            case "WhiteFat":
                drawCenterLine(canvas, paint, Color.WHITE, 150, 14.0f * scale);
                break;
            case "YellowThin":
                drawCenterLine(canvas, paint, Color.YELLOW, 45, strokeWidth);
                break;
            case "YellowMedium":
                drawCenterLine(canvas, paint, Color.YELLOW, 100, 6.0f * scale);
                break;
            case "YellowFat":
                drawCenterLine(canvas, paint, Color.YELLOW, 150, 14.0f * scale);
                break;
            case "RedThin":
                drawCenterLine(canvas, paint, Color.RED, 45, strokeWidth);
                break;
            case "RedMedium":
                drawCenterLine(canvas, paint, Color.RED, 100, 6.0f * scale);
                break;
            case "RedFat":
                drawCenterLine(canvas, paint, Color.RED, 150, 14.0f * scale);
                break;
        }
    }

    private static void drawCenterLine(Canvas canvas, Paint paint, int color, int alpha, float strokeWidth) {
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(strokeWidth);
        drawLine(canvas, paint, canvas.getWidth() / 2.0f, 0.0f, canvas.getWidth() / 2.0f, canvas.getHeight() - 1.0f);
        paint.setAlpha(255);
    }

    private static void drawLine(Canvas canvas, Paint paint, float startX, float startY, float stopX, float stopY) {
        canvas.drawLine(startX, startY, stopX, stopY, paint);
    }

    private void sendText(OutputStream outputStream, int statusCode, String reason, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        sendBytes(outputStream, statusCode, reason, contentType, bodyBytes);
    }

    private void sendBytes(OutputStream outputStream, int statusCode, String reason, String contentType, byte[] bodyBytes) throws IOException {
        String headers = "HTTP/1.1 " + statusCode + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        outputStream.write(headers.getBytes(StandardCharsets.UTF_8));
        outputStream.write(bodyBytes);
        outputStream.flush();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatFloat(float value) {
        if( Math.abs(value - Math.round(value)) < 1.0e-5f ) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String jsonEscape(String value) {
        if( value == null ) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String reasonPhrase(int statusCode) {
        switch(statusCode) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            default:
                return "Error";
        }
    }

    private String buildControlPage() {
        return "<!doctype html><html><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Photo Finish Remote</title>"
                + "<style>"
                + ":root{color-scheme:dark;font-family:system-ui,-apple-system,Segoe UI,sans-serif;background:#111;color:#f6f6f6}"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#111}"
                + "main{width:min(640px,100%);padding:18px;box-sizing:border-box}"
                + "h1{font-size:24px;margin:0 0 14px;font-weight:650}"
                + "h2{font-size:15px;margin:18px 0 8px;color:#d8d8d8;font-weight:650}"
                + ".status{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 18px}"
                + ".pill{border:1px solid #555;border-radius:999px;padding:7px 10px;font-size:14px;background:#1b1b1b}"
                + "label{display:block;font-size:13px;color:#cfcfcf;margin:0 0 8px}"
                + "input{width:100%;box-sizing:border-box;font-size:20px;padding:14px;border-radius:8px;border:1px solid #555;background:#191919;color:#fff;margin-bottom:14px}"
                + ".preview{display:none;margin:0 0 12px}"
                + ".preview.active{display:block}"
                + "#preview_image{display:block;width:100%;height:auto;max-height:70vh;object-fit:contain;background:#050505;border:1px solid #333;border-radius:8px;box-sizing:border-box}"
                + ".grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}"
                + "button{border:0;border-radius:8px;padding:16px 12px;font-size:17px;font-weight:650;color:#fff;background:#2c6bed;cursor:pointer;touch-action:manipulation;transition:transform .06s ease,filter .12s ease,opacity .12s ease}"
                + "button:active,button.pressed{transform:scale(.97);filter:brightness(1.22)}"
                + "button:disabled{opacity:.58;cursor:wait}"
                + "button.secondary{background:#343434}"
                + "button.danger{background:#c62828}"
                + "button.active{outline:2px solid #fff;filter:brightness(1.16)}"
                + "button.full{grid-column:1/-1}"
                + ".control_row{display:flex;flex-wrap:wrap;gap:8px}"
                + ".control_row button{background:#343434;padding:11px 12px;font-size:14px}"
                + ".control_row button.primary{background:#2c6bed}"
                + ".unavailable{color:#a8a8a8;font-size:14px;padding:6px 0}"
                + "#message{min-height:24px;margin-top:14px;color:#d8d8d8;font-size:14px}"
                + "@media(max-width:420px){.grid{grid-template-columns:1fr}button.full{grid-column:auto}}"
                + "</style></head><body><main>"
                + "<h1>Photo Finish Remote</h1>"
                + "<div class=\"status\"><span class=\"pill\" id=\"mode\">Mode: --</span><span class=\"pill\" id=\"recording\">Recording: --</span></div>"
                + "<label for=\"pin\">PIN from the camera phone</label>"
                + "<input id=\"pin\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"12\" placeholder=\"PIN\">"
                + "<div class=\"preview\" id=\"preview_panel\"><img id=\"preview_image\" alt=\"\"></div>"
                + "<div class=\"grid\">"
                + "<button data-command=\"/api/photo\">Take photo</button>"
                + "<button data-command=\"/api/video/start\">Start video</button>"
                + "<button class=\"danger\" data-command=\"/api/video/stop\">Stop video</button>"
                + "<button class=\"secondary\" id=\"preview_button\" type=\"button\">Start preview</button>"
                + "<button class=\"secondary full\" data-command=\"/api/mode/toggle\">Switch photo/video mode</button>"
                + "<button class=\"secondary\" id=\"standby_button\" data-command=\"/api/standby/toggle\">Stand-by mode</button>"
                + "<button class=\"secondary\" id=\"dim_button\" data-command=\"/api/screen/dim/toggle\">Dim screen</button>"
                + "</div>"
                + "<section><h2>Recording speed</h2><div class=\"control_row\" id=\"capture_rate_controls\"></div></section>"
                + "<section><h2>ISO</h2><div class=\"control_row\" id=\"iso_controls\"></div></section>"
                + "<section><h2>Shutter speed</h2><div class=\"control_row\" id=\"shutter_controls\"></div></section>"
                + "<div id=\"message\"></div>"
                + "<script>"
                + "const pin=document.getElementById('pin');const msg=document.getElementById('message');"
                + "const standbyButton=document.getElementById('standby_button');const dimButton=document.getElementById('dim_button');"
                + "const previewButton=document.getElementById('preview_button');const previewPanel=document.getElementById('preview_panel');const previewImage=document.getElementById('preview_image');"
                + "const captureRateControls=document.getElementById('capture_rate_controls');const isoControls=document.getElementById('iso_controls');const shutterControls=document.getElementById('shutter_controls');"
                + "let currentStatus=null;let previewEnabled=false;let previewTimer=null;let previewInFlight=false;"
                + "const urlPin=new URLSearchParams(location.search).get('pin');"
                + "pin.value=(urlPin&&urlPin.trim())||localStorage.getItem('photo_finish_remote_pin')||'';"
                + "if(pin.value) localStorage.setItem('photo_finish_remote_pin',pin.value.trim());"
                + "pin.addEventListener('input',()=>localStorage.setItem('photo_finish_remote_pin',pin.value.trim()));"
                + "function setMsg(t){msg.textContent=t||'';}"
                + "function commandButtons(){return [...document.querySelectorAll('button[data-command]')];}"
                + "function setBusy(busy){commandButtons().forEach(b=>b.disabled=busy);}"
                + "function withPin(path){const p=pin.value.trim();if(!p){setMsg('Enter the PIN shown on the camera phone.');return null;}return path+(path.includes('?')?'&':'?')+'pin='+encodeURIComponent(p);}"
                + "async function request(path,method){const p=pin.value.trim();if(!p){setMsg('Enter the PIN shown on the camera phone.');return null;}"
                + "const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),8000);"
                + "try{const r=await fetch(path+(path.includes('?')?'&':'?')+'pin='+encodeURIComponent(p),{method:method||'GET',cache:'no-store',signal:controller.signal});"
                + "const j=await r.json().catch(()=>({ok:false,error:'bad_response'}));"
                + "if(!j.ok){setMsg(j.error==='invalid_pin'?'Invalid PIN. Use the latest URL or PIN shown on the camera phone.':(j.message||j.error||'Command failed'));return null;}return j;}"
                + "catch(e){setMsg(e.name==='AbortError'?'No response from camera phone. Keep the app open on the camera screen.':'Network error. Check Wi-Fi/hotspot and the URL.');return null;}"
                + "finally{clearTimeout(timeout);}}"
                + "function commandPath(button){if(button===standbyButton)return currentStatus&&currentStatus.standbyMode?'/api/standby/off':'/api/standby/on';"
                + "if(button===dimButton)return currentStatus&&currentStatus.screenDimmed?'/api/screen/dim/off':'/api/screen/dim/on';return button.dataset.command;}"
                + "async function command(path,button){button.classList.add('pressed');setBusy(true);setMsg('Sending command...');"
                + "const j=await request(path,'POST');if(j){setMsg(j.message||'OK');await refresh();}"
                + "setBusy(false);setTimeout(()=>button.classList.remove('pressed'),160);}"
                + "function clear(el){while(el.firstChild)el.removeChild(el.firstChild);}"
                + "function addControl(parent,label,path,active){const b=document.createElement('button');b.type='button';b.dataset.command=path;b.textContent=label;if(active)b.classList.add('active','primary');parent.appendChild(b);}"
                + "function showUnavailable(parent,text){const span=document.createElement('span');span.className='unavailable';span.textContent=text;parent.appendChild(span);}"
                + "function renderControls(j){const c=j.controls||{};clear(captureRateControls);clear(isoControls);clear(shutterControls);"
                + "(c.captureRates||[]).forEach(r=>addControl(captureRateControls,r.label,'/api/capture-rate?value='+r.value,Math.abs((c.captureRate||1)-r.value)<0.00001));"
                + "if(c.manualExposureSupported){(c.isoPresets||['auto']).forEach(v=>addControl(isoControls,v==='auto'?'Auto':'ISO '+v,'/api/iso?value='+encodeURIComponent(v),String(c.iso)===String(v)));}else{showUnavailable(isoControls,'Manual ISO unavailable');}"
                + "if(c.exposureTimeSupported){(c.shutterPresets||[]).forEach(s=>addControl(shutterControls,s.label,'/api/shutter-speed?value='+encodeURIComponent(s.value),s.value==='auto'?c.exposureTimeLabel==='Auto':String(c.exposureTimeNs)===String(s.value)));}else{showUnavailable(shutterControls,'Manual shutter unavailable');}}"
                + "function previewPath(){const url=withPin('/api/preview.jpg?t='+Date.now());return url;}"
                + "function schedulePreview(){clearTimeout(previewTimer);if(previewEnabled)previewTimer=setTimeout(loadPreview,800);}"
                + "function loadPreview(){if(!previewEnabled||previewInFlight)return;const url=previewPath();if(!url){stopPreview();return;}previewInFlight=true;previewImage.onload=()=>{previewInFlight=false;schedulePreview();};previewImage.onerror=()=>{previewInFlight=false;setMsg('Preview unavailable.');schedulePreview();};previewImage.src=url;}"
                + "function startPreview(){previewEnabled=true;previewPanel.classList.add('active');previewButton.textContent='Stop preview';loadPreview();}"
                + "function stopPreview(){previewEnabled=false;previewInFlight=false;clearTimeout(previewTimer);previewPanel.classList.remove('active');previewImage.removeAttribute('src');previewButton.textContent='Start preview';}"
                + "previewButton.addEventListener('click',()=>previewEnabled?stopPreview():startPreview());"
                + "document.addEventListener('click',e=>{const button=e.target.closest('button[data-command]');if(button)command(commandPath(button),button);});"
                + "async function refresh(){const j=await request('/api/status','GET');if(!j)return;"
                + "currentStatus=j;"
                + "document.getElementById('mode').textContent='Mode: '+j.mode;"
                + "document.getElementById('recording').textContent='Recording: '+(j.recording?'yes':'no');"
                + "standbyButton.textContent=j.standbyMode?'Exit stand-by':'Stand-by mode';"
                + "dimButton.textContent=j.screenDimmed?'Restore brightness':'Dim screen';"
                + "if(j.standbyMode&&previewEnabled)stopPreview();renderControls(j);}"
                + "setInterval(refresh,1500);if(pin.value)refresh();"
                + "</script></main></body></html>";
    }

    public static String getOrCreatePin(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String pin = sharedPreferences.getString(PreferenceKeys.WebRemotePin, null);
        if( pin == null || pin.length() == 0 ) {
            pin = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            sharedPreferences.edit().putString(PreferenceKeys.WebRemotePin, pin).apply();
        }
        return pin;
    }

    public static String getAccessSummary(Context context) {
        StringBuilder builder = new StringBuilder();
        String pin = getOrCreatePin(context);
        builder.append(context.getString(R.string.preference_web_remote_pin_summary, pin));
        List<String> urls = getAccessUrls();
        if( urls.isEmpty() ) {
            builder.append("\n").append(context.getString(R.string.preference_web_remote_no_network_summary));
        }
        else {
            builder.append("\n").append(context.getString(R.string.preference_web_remote_open_summary));
            for(String url : urls) {
                builder.append("\n").append(url).append("/?pin=").append(pin);
            }
        }
        return builder.toString();
    }

    public static List<String> getAccessUrls() {
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while( interfaces.hasMoreElements() ) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if( !networkInterface.isUp() || networkInterface.isLoopback() ) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while( addresses.hasMoreElements() ) {
                    InetAddress address = addresses.nextElement();
                    if( address instanceof Inet4Address && !address.isLoopbackAddress() ) {
                        urls.add("http://" + address.getHostAddress() + ":" + activePort);
                    }
                }
            }
        }
        catch(SocketException e) {
            if( MyDebug.LOG ) {
                Log.d(TAG, "failed to enumerate network interfaces: " + e.getMessage());
            }
        }
        return urls;
    }

    private interface UiCommand {
        CommandResult run();
    }

    private interface ReadyCondition {
        boolean isReady();
    }

    private static class CaptureResult {
        final byte[] jpeg;
        final String error;

        CaptureResult(byte[] jpeg, String error) {
            this.jpeg = jpeg;
            this.error = error;
        }
    }

    private static class RequestTarget {
        final String path;
        final Map<String, String> queryParams;

        RequestTarget(String path, Map<String, String> queryParams) {
            this.path = path;
            this.queryParams = queryParams;
        }
    }

    private static class CommandResult {
        final boolean ok;
        final String error;
        final String message;

        private CommandResult(boolean ok, String error, String message) {
            this.ok = ok;
            this.error = error;
            this.message = message;
        }

        static CommandResult ok(String message) {
            return new CommandResult(true, null, message);
        }

        static CommandResult error(String error, String message) {
            return new CommandResult(false, error, message);
        }

        String toJson() {
            if( ok ) {
                return "{\"ok\":true,\"message\":\"" + escapeJson(message) + "\"}";
            }
            return "{\"ok\":false,\"error\":\"" + escapeJson(error) + "\",\"message\":\"" + escapeJson(message) + "\"}";
        }

        private static String escapeJson(String value) {
            if( value == null ) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
