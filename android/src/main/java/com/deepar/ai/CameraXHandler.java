package com.deepar.ai;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.media.Image; // Add this import
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.TorchState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import ai.deepar.ar.CameraResolutionPreset;
import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class CameraXHandler implements MethodChannel.MethodCallHandler {
    private final Activity activity;
    private DeepAR deepAR;
    private final long textureId;
    private ProcessCameraProvider processCameraProvider;
    private ListenableFuture<ProcessCameraProvider> future;
    private ByteBuffer[] buffers;
    private int currentBuffer = 0;
    private static final int NUMBER_OF_BUFFERS = 2;
    private final CameraResolutionPreset resolutionPreset;
    private int defaultLensFacing = CameraSelector.LENS_FACING_FRONT;
    private int lensFacing = defaultLensFacing;
    private Camera camera;

    CameraXHandler(Activity activity, long textureId, DeepAR deepAR, CameraResolutionPreset cameraResolutionPreset) {
        this.activity = activity;
        this.deepAR = deepAR;
        this.textureId = textureId;
        this.resolutionPreset = cameraResolutionPreset;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case MethodStrings.startCamera:
                startNative(result);
                break;
            case "flip_camera":
                flipCamera();
                result.success(true);
                break;
            case "toggle_flash":
                boolean isFlash = toggleFlash();
                result.success(isFlash);
                break;
            case "destroy":
                destroy();
                result.success("SHUTDOWN");
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private boolean toggleFlash() {
        try {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                boolean isFlashOn = camera.getCameraInfo().getTorchState().getValue() == TorchState.ON;
                camera.getCameraControl().enableTorch(!isFlashOn);
                return !isFlashOn;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void flipCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_FRONT) ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        try {
            ProcessCameraProvider cameraProvider = future.get();
            cameraProvider.unbindAll();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
        startNative(null);
    }

    private void startNative(MethodChannel.Result result) {
        future = ProcessCameraProvider.getInstance(activity);
        Executor executor = ContextCompat.getMainExecutor(activity);

        int width, height;
        int orientation = getScreenOrientation();
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            width = resolutionPreset.getWidth();
            height = resolutionPreset.getHeight();
        } else {
            width = resolutionPreset.getHeight();
            height = resolutionPreset.getWidth();
        }

        buffers = new ByteBuffer[NUMBER_OF_BUFFERS];
        for (int i = 0; i < NUMBER_OF_BUFFERS; i++) {
            buffers[i] = ByteBuffer.allocateDirect(CameraResolutionPreset.P1920x1080.getWidth() * CameraResolutionPreset.P1920x1080.getHeight() * 3);
            buffers[i].order(ByteOrder.nativeOrder());
            buffers[i].position(0);
        }

        future.addListener(() -> {
            try {
                processCameraProvider = future.get();
                Size cameraResolution = new Size(width, height);
                ImageAnalysis.Analyzer analyzer = new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        byte[] nv21Bytes = convertYUV420888ToNV21(image.getImage());

                        buffers[currentBuffer].put(nv21Bytes);
                        buffers[currentBuffer].position(0);

                        if (deepAR != null) {
                            try {
                                deepAR.receiveFrame(buffers[currentBuffer], image.getWidth(), image.getHeight(),
                                        image.getImageInfo().getRotationDegrees(), lensFacing == CameraSelector.LENS_FACING_FRONT,
                                        DeepARImageFormat.YUV_420_888, image.getPlanes()[1].getPixelStride());
                            } catch (Exception e) {
                                e.printStackTrace();
                                Log.e("Error", "" + e);
                            }
                        }

                        currentBuffer = (currentBuffer + 1) % NUMBER_OF_BUFFERS;
                        image.close();
                    }
                };

                CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(cameraResolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(executor, analyzer);

                processCameraProvider.unbindAll();

                camera = processCameraProvider.bindToLifecycle((LifecycleOwner) activity, cameraSelector, imageAnalysis);

                if (result != null) {
                    result.success(textureId);
                }

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, executor);
    }

    private byte[] convertYUV420888ToNV21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;

        byte[] nv21 = new byte[ySize + uvSize * 2];
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int yStride = image.getPlanes()[0].getRowStride();
        int uvStride = image.getPlanes()[1].getRowStride();
        int uvPixelStride = image.getPlanes()[1].getPixelStride();

        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yStride);
            yBuffer.get(nv21, row * width, width);
        }

        int uvPos = ySize;
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int v = vBuffer.get(row * uvStride + col * uvPixelStride) & 0xFF;
                int u = uBuffer.get(row * uvStride + col * uvPixelStride) & 0xFF;
                nv21[uvPos++] = (byte) v;
                nv21[uvPos++] = (byte) u;
            }
        }

        return nv21;
    }

    private void destroy() {
        try {
            ProcessCameraProvider cameraProvider = future.get();
            cameraProvider.unbindAll();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
        if (deepAR != null) {
            deepAR.setAREventListener(null);
            deepAR.release();
            deepAR = null;
        }
    }

    private int getScreenOrientation() {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int orientation;

        if ((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) && height > width ||
                (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) && width > height) {
            switch (rotation) {
                case Surface.ROTATION_0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_180:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                case Surface.ROTATION_270:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                default:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
            }
        } else {
            switch (rotation) {
                case Surface.ROTATION_0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
                case Surface.ROTATION_180:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                case Surface.ROTATION_270:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                default:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
            }
        }

        return orientation;
    }
}
