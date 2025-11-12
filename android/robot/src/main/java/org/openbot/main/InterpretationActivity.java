package org.openbot.main; // Paquete de "Omnibot"

// Todos los imports necesarios de tu app de OpenCV
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.openbot.R; // Importante: Usar el R de "Omnibot"
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Nombre de clase cambiado a InterpretationActivity
public class InterpretationActivity extends AppCompatActivity {

    private static final String TAG = "OCV_CameraX_Final";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private PreviewView previewView;
    private ImageView processedImageView;
    private ExecutorService cameraExecutor;

    private Mat yuvMat, grayMat, rgbaMat;
    private Bitmap processedBitmap;

    // Carga de OpenCV
    static {
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV cargado exitosamente.");
        } else {
            Log.e(TAG, "Fallo al cargar OpenCV.");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Usar el layout de "Omnibot"
        setContentView(R.layout.activity_interpretation);

        // Usamos findViewById, igual que en tu app original
        // (Esto es más simple que re-configurar ViewBinding)
        previewView = findViewById(R.id.camera_preview);
        processedImageView = findViewById(R.id.processed_image_view);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .addCameraFilter(cameraInfos -> {
                            for (CameraInfo cameraInfo : cameraInfos) {
                                Integer lensFacing = cameraInfo.getLensFacing();
                                if (lensFacing != null && lensFacing == CameraSelector.LENS_FACING_BACK) {
                                    return java.util.Collections.singletonList(cameraInfo);
                                }
                            }
                            return java.util.Collections.emptyList();
                        })
                        .build();

                ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalyzer.setAnalyzer(cameraExecutor, new OpenCVAnalyzer());

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer);
                Log.d(TAG, "CameraX iniciado y enlazado al ciclo de vida.");

            } catch (Exception e) {
                Log.e(TAG, "Fallo al iniciar o enlazar CameraX.", e);
                runOnUiThread(() -> Toast.makeText(this, "No se pudo iniciar la cámara.", Toast.LENGTH_LONG).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private class OpenCVAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy image) {
            // Inicialización de Mats (matrices)
            if (yuvMat == null) {
                yuvMat = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CvType.CV_8UC1);
                grayMat = new Mat();
                rgbaMat = new Mat();
            }

            // Conversión de YUV (formato de CameraX) a NV21
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
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
            yuvMat.put(0, 0, nv21);

            // Procesamiento con OpenCV
            Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21); // NV21 a RGBA
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY);    // RGBA a Gris

            // Rotar la imagen 90 grados (necesario por la orientación del sensor)
            Core.rotate(grayMat, grayMat, Core.ROTATE_90_CLOCKWISE);

            // Crear/reusar el Bitmap para mostrar en el ImageView
            if (processedBitmap == null || processedBitmap.getWidth() != grayMat.cols() || processedBitmap.getHeight() != grayMat.rows()) {
                processedBitmap = Bitmap.createBitmap(grayMat.cols(), grayMat.rows(), Bitmap.Config.ARGB_8888);
            }

            // Convertir Mat de OpenCV a Bitmap de Android
            Utils.matToBitmap(grayMat, processedBitmap);

            // Actualizar el ImageView en el hilo principal
            runOnUiThread(() -> processedImageView.setImageBitmap(processedBitmap));

            image.close();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permisos no concedidos por el usuario.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
