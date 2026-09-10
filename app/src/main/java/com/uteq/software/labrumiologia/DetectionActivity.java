package com.uteq.software.labrumiologia;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.uteq.software.labrumiologia.detection.DetectionTracker;
import com.uteq.software.labrumiologia.detection.YoloDetector;
import com.uteq.software.labrumiologia.model.Detection;
import com.uteq.software.labrumiologia.ui.DetectionAdapter;
import com.uteq.software.labrumiologia.ui.DetectionOverlayView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DetectionActivity extends AppCompatActivity {
    public static final String EXTRA_EQUIPMENT_ID = "equipment_id";
    public static final String EXTRA_EQUIPMENT_LABEL = "equipment_label";
    public static final String EXTRA_CONFIDENCE = "confidence";

    private static final int REQ_CAMERA = 100;

    private PreviewView previewView;
    private DetectionOverlayView overlayView;
    private TextView statusText;
    private MaterialButton btnInfo;
    private DetectionAdapter adapter;
    private RecyclerView detectionsList;

    private YoloDetector detector;
    private final DetectionTracker tracker = new DetectionTracker();
    private ExecutorService analysisExecutor;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private Camera camera;
    private boolean flashOn = false;

    private final List<Detection> latestDetections = new ArrayList<>();
    private int selectedIndex = -1;
    private String selectedClassId = null;
    private boolean selectedByUser = false;
    private boolean modelAvailable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection);

        previewView = findViewById(R.id.previewView);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        overlayView = findViewById(R.id.overlayView);
        statusText = findViewById(R.id.statusText);
        btnInfo = findViewById(R.id.btnInfo);
        detectionsList = findViewById(R.id.detectionsList);
        detectionsList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        );
        adapter = new DetectionAdapter(this::selectDetection);
        detectionsList.setAdapter(adapter);
        detectionsList.setVisibility(View.GONE);

        overlayView.setOnDetectionTapListener(this::selectDetection);
        btnInfo.setOnClickListener(v -> openDetail());
        findViewById(R.id.btnFlash).setOnClickListener(v -> toggleFlash());
        analysisExecutor = Executors.newSingleThreadExecutor();

        try {
            detector = new YoloDetector(this);
            modelAvailable = detector.isReady();
            statusText.setText(R.string.detecting);
        } catch (Exception e) {
            modelAvailable = false;
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("Falta") || msg.contains("assets")) {
                statusText.setText(R.string.model_missing);
                Toast.makeText(this, R.string.model_missing, Toast.LENGTH_LONG).show();
            } else {
                statusText.setText(getString(R.string.model_load_error, msg));
                Toast.makeText(this, getString(R.string.model_load_error, msg), Toast.LENGTH_LONG).show();
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void selectDetection(Detection detection, int index) {
        selectedIndex = index;
        selectedClassId = detection != null ? detection.classId : null;
        selectedByUser = detection != null && index >= 0;
        overlayView.setSelectedIndex(index);
        adapter.submit(new ArrayList<>(latestDetections), selectedIndex);
        btnInfo.setEnabled(detection != null);
        btnInfo.setVisibility(detection != null ? View.VISIBLE : View.GONE);
        if (detection != null) {
            btnInfo.setText(getString(R.string.ver_equipo, detection.label));
        }
    }

    private void openDetail() {
        if (selectedIndex < 0 || selectedIndex >= latestDetections.size()) {
            Toast.makeText(this, R.string.select_equipment, Toast.LENGTH_SHORT).show();
            return;
        }
        Detection d = latestDetections.get(selectedIndex);
        Intent i = new Intent(this, EquipmentDetailActivity.class);
        i.putExtra(EXTRA_EQUIPMENT_ID, d.classId);
        i.putExtra(EXTRA_EQUIPMENT_LABEL, d.label);
        i.putExtra(EXTRA_CONFIDENCE, d.confidence);
        startActivity(i);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                bindCamera(provider);
            } catch (Exception e) {
                statusText.setText("Error al iniciar cámara: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // CameraX hace la conversión nativa; evita comprimir cada frame a JPEG.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(new android.util.Size(640, 480))
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(
                this,
                new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build(),
                preview,
                analysis
        );
    }

    private void toggleFlash() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        flashOn = !flashOn;
        camera.getCameraControl().enableTorch(flashOn);
        android.widget.ImageButton btnFlash = findViewById(R.id.btnFlash);
        btnFlash.setImageResource(flashOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeFrame(@NonNull ImageProxy image) {
        if (!modelAvailable || detector == null || !busy.compareAndSet(false, true)) {
            image.close();
            return;
        }
        try {
            Bitmap bitmap = image.toBitmap();
            if (bitmap == null) return;
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            List<Detection> raw = detector.detect(bitmap);
            List<Detection> detections = tracker.update(raw);
            if (raw.isEmpty()) {
                // Sin evidencia del modelo: limpiar selección y no arrastrar nombres viejos.
                tracker.reset();
                detections = new ArrayList<>();
            }
            int w = detector.getSourceWidth();
            int h = detector.getSourceHeight();
            bitmap.recycle();
            List<Detection> finalDetections = detections;
            runOnUiThread(() -> {
                if (!isDestroyed()) showDetections(finalDetections, w, h);
            });
        } catch (Exception e) {
            runOnUiThread(() -> statusText.setText("Error de inferencia: " + e.getMessage()));
        } finally {
            busy.set(false);
            image.close();
        }
    }

    private static Bitmap yuvToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length < 3) return null;
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);
        byte[] jpeg = out.toByteArray();
        return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    private void showDetections(List<Detection> detections, int srcW, int srcH) {
        latestDetections.clear();
        latestDetections.addAll(visibleDetections(detections, srcW, srcH));
        if (latestDetections.isEmpty()) {
            selectedIndex = -1;
            selectedClassId = null;
            selectedByUser = false;
        } else if (latestDetections.size() == 1) {
            // Una sola máquina no requiere una selección adicional.
            selectedIndex = 0;
            selectedClassId = latestDetections.get(0).classId;
            selectedByUser = false;
        } else if (selectedByUser && selectedClassId != null) {
            int byClass = DetectionTracker.indexOfClass(latestDetections, selectedClassId);
            selectedIndex = byClass;
            if (selectedIndex >= 0) {
                selectedClassId = latestDetections.get(selectedIndex).classId;
            } else {
                selectedClassId = null;
                selectedByUser = false;
            }
        } else {
            // Con varias máquinas, esperar una elección explícita del usuario.
            selectedIndex = -1;
            selectedClassId = null;
        }
        if (latestDetections.isEmpty()) {
            selectedIndex = -1;
            selectedClassId = null;
            btnInfo.setEnabled(false);
            btnInfo.setVisibility(View.GONE);
            statusText.setText(R.string.no_detections);
            findViewById(R.id.statusSubtitle).setVisibility(View.VISIBLE);
        } else {
            btnInfo.setEnabled(selectedIndex >= 0);
            btnInfo.setVisibility(selectedIndex >= 0 ? View.VISIBLE : View.GONE);
            if (selectedIndex >= 0) {
                btnInfo.setText(getString(R.string.ver_equipo, latestDetections.get(selectedIndex).label));
            }
            statusText.setText(getString(R.string.equipos_encontrados, latestDetections.size()));
            findViewById(R.id.statusSubtitle).setVisibility(View.GONE);
        }
        overlayView.setImageSize(srcW, srcH);
        overlayView.setDetections(latestDetections, selectedIndex);
        adapter.submit(latestDetections, selectedIndex);
        detectionsList.setVisibility(latestDetections.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Descarta cajas que FILL_CENTER deja casi totalmente fuera de la vista previa y filtra duplicados por clase. */
    private List<Detection> visibleDetections(List<Detection> detections, int srcW, int srcH) {
        List<Detection> visible = new ArrayList<>();
        float viewW = overlayView.getWidth();
        float viewH = overlayView.getHeight();
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) return detections;
        float scale = Math.max(viewW / srcW, viewH / srcH);
        float visibleW = viewW / scale;
        float visibleH = viewH / scale;
        float left = (srcW - visibleW) * 0.5f;
        float top = (srcH - visibleH) * 0.5f;
        android.graphics.RectF viewport = new android.graphics.RectF(left, top, left + visibleW, top + visibleH);
        
        // Mapa para conservar solo la detección con mayor confianza por cada clase
        java.util.Map<String, Detection> bestByClass = new java.util.HashMap<>();

        for (Detection d : detections) {
            if (d.box == null) continue;
            android.graphics.RectF intersection = new android.graphics.RectF(d.box);
            if (!intersection.intersect(viewport)) continue;
            float area = Math.max(1f, d.box.width() * d.box.height());
            if ((intersection.width() * intersection.height()) / area >= 0.60f) {
                Detection existing = bestByClass.get(d.classId);
                if (existing == null || d.confidence > existing.confidence) {
                    bestByClass.put(d.classId, d);
                }
            }
        }
        
        visible.addAll(bestByClass.values());
        // Opcional: ordenar por confianza descendente
        visible.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        return visible;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (detector != null) detector.close();
    }
}
