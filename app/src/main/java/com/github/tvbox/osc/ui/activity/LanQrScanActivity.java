package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.databinding.ActivityLanQrScanBinding;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.LanPairQr;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.PlanarYUVLuminanceSource;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera QR scanner for LAN pairing. Returns validated text; the host starts the connection. */
public final class LanQrScanActivity extends BaseVbActivity<ActivityLanQrScanBinding> {
    public static final String EXTRA_QR_RESULT = "lan_qr_result";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean decoding = new AtomicBoolean();
    private final AtomicBoolean found = new AtomicBoolean();
    private final MultiFormatReader qrReader = new MultiFormatReader();
    private final ActivityResultLauncher<String> requestCamera = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                permissionPending = false;
                if (granted) openIfReady();
                else showCameraError("需要相机权限才能扫描二维码");
            });

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader frames;
    private Surface previewSurface;
    private boolean active;
    private boolean opening;
    private boolean permissionPending;
    private int cameraEpoch;
    private long lastDecodeAt;

    @Override protected void init() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        qrReader.setHints(hints);
        mBinding.btnRetryScan.setOnClickListener(v -> openIfReady());
        mBinding.qrCameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
                openIfReady();
            }

            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) { }

            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
                ++cameraEpoch;
                closeCamera();
                return true;
            }

            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) { }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        found.set(false);
        openIfReady();
    }

    @Override protected void onPause() {
        active = false;
        ++cameraEpoch;
        closeCamera();
        super.onPause();
    }

    private void openIfReady() {
        if (!active || opening || camera != null || !mBinding.qrCameraPreview.isAvailable()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionPending) {
                permissionPending = true;
                requestCamera.launch(Manifest.permission.CAMERA);
            }
            return;
        }
        final int epoch = ++cameraEpoch;
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) throw new IllegalStateException("找不到相机服务");
            String cameraId = chooseCamera(manager);
            if (cameraId == null) throw new IllegalStateException("这台设备没有可用相机");
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size frameSize = chooseSize(characteristics);
            frames = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
            frames.setOnImageAvailableListener(reader -> scanFrame(reader, epoch), main);
            mBinding.qrCameraPreview.getSurfaceTexture().setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
            previewSurface = new Surface(mBinding.qrCameraPreview.getSurfaceTexture());
            opening = true;
            mBinding.btnRetryScan.setVisibility(View.GONE);
            mBinding.tvScanStatus.setText("正在打开相机…");
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice opened) {
                    opening = false;
                    if (!active || epoch != cameraEpoch) {
                        opened.close();
                        return;
                    }
                    camera = opened;
                    startPreview(epoch);
                }

                @Override public void onDisconnected(@NonNull CameraDevice disconnected) {
                    disconnected.close();
                    if (epoch == cameraEpoch) {
                        closeCamera();
                        showCameraError("相机连接已断开，请重试");
                    }
                }

                @Override public void onError(@NonNull CameraDevice failed, int error) {
                    failed.close();
                    if (epoch == cameraEpoch) {
                        closeCamera();
                        showCameraError("相机无法打开，请重试");
                    }
                }
            }, main);
        } catch (Exception error) {
            closeCamera();
            showCameraError("相机无法打开，请重试");
        }
    }

    private void startPreview(int epoch) {
        if (camera == null || frames == null || previewSurface == null) return;
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(frames.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(previewSurface, frames.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession configured) {
                            if (!active || epoch != cameraEpoch) {
                                configured.close();
                                return;
                            }
                            session = configured;
                            try {
                                session.setRepeatingRequest(request.build(), null, main);
                                mBinding.tvScanStatus.setText("将二维码放入取景框");
                            } catch (CameraAccessException | IllegalStateException error) {
                                closeCamera();
                                showCameraError("相机预览失败，请重试");
                            }
                        }

                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession failed) {
                            if (epoch == cameraEpoch) {
                                closeCamera();
                                showCameraError("相机预览失败，请重试");
                            }
                        }
                    }, main);
        } catch (CameraAccessException | IllegalStateException error) {
            closeCamera();
            showCameraError("相机预览失败，请重试");
        }
    }

    private void scanFrame(ImageReader reader, int epoch) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !active || epoch != cameraEpoch || found.get()
                    || decoding.get() || SystemClock.elapsedRealtime() - lastDecodeAt < 180) return;
            if (!decoding.compareAndSet(false, true)) return;
            lastDecodeAt = SystemClock.elapsedRealtime();
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] luma = copyLuma(image);
            HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
                try {
                    String value = decode(luma, width, height);
                    if (value != null && LanPairQr.decode(value) == null) {
                        main.post(() -> {
                            if (active && epoch == cameraEpoch) mBinding.tvScanStatus.setText("这不是 MBox 配对二维码");
                        });
                    } else if (value != null && found.compareAndSet(false, true)) {
                        main.post(() -> {
                            if (!active || epoch != cameraEpoch) return;
                            Intent result = new Intent().putExtra(EXTRA_QR_RESULT, value);
                            setResult(Activity.RESULT_OK, result);
                            finish();
                        });
                    }
                } catch (RuntimeException ignored) {
                    // A malformed frame should not take down the camera page.
                } finally {
                    decoding.set(false);
                }
            });
        } catch (Exception error) {
            decoding.set(false);
        } finally {
            if (image != null) image.close();
        }
    }

    private String decode(byte[] luma, int width, int height) {
        try {
            BinaryBitmap image = binary(luma, width, height);
            Result result = qrReader.decodeWithState(image);
            return result.getText();
        } catch (ReaderException ignored) {
            qrReader.reset();
        }
        try {
            byte[] rotated = rotate(luma, width, height);
            Result result = qrReader.decodeWithState(binary(rotated, height, width));
            return result.getText();
        } catch (ReaderException ignored) {
            return null;
        } finally {
            qrReader.reset();
        }
    }

    private static BinaryBitmap binary(byte[] luma, int width, int height) {
        return new BinaryBitmap(new HybridBinarizer(
                new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)));
    }

    private static byte[] rotate(byte[] source, int width, int height) {
        byte[] target = new byte[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                target[x * height + height - y - 1] = source[y * width + x];
            }
        }
        return target;
    }

    private static byte[] copyLuma(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer source = plane.getBuffer();
        int stride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] target = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * stride;
            for (int x = 0; x < width; x++) {
                target[y * width + x] = source.get(offset + x * pixelStride);
            }
        }
        return target;
    }

    private static String chooseCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        if (ids.length == 0) return null;
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids[0];
    }

    private static Size chooseSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("相机不支持扫码预览");
        Size chosen = null;
        long best = Long.MAX_VALUE;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (area >= 640L * 480L && area <= 1280L * 720L && area < best) {
                chosen = size;
                best = area;
            }
        }
        if (chosen != null) return chosen;
        chosen = sizes[0];
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight()
                    < (long) chosen.getWidth() * chosen.getHeight()) chosen = size;
        }
        return chosen;
    }

    private void closeCamera() {
        opening = false;
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (frames != null) {
            frames.close();
            frames = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void showCameraError(String message) {
        if (!active) return;
        mBinding.tvScanStatus.setText(message);
        mBinding.btnRetryScan.setVisibility(View.VISIBLE);
    }
}
