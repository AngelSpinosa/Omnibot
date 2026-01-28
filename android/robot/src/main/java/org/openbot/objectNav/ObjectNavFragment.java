package org.openbot.objectNav;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;
import androidx.navigation.Navigation;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.openbot.R;
import org.openbot.common.CameraFragment;
import org.openbot.databinding.FragmentObjectNavBinding;
import org.openbot.env.BorderedText;
import org.openbot.env.ImageUtils;
import org.openbot.tflite.Detector;
import org.openbot.tflite.Model;
import org.openbot.tflite.Network;
import org.openbot.tracking.MultiBoxTracker;
import org.openbot.utils.CameraUtils;
import org.openbot.utils.Constants;
import org.openbot.utils.Enums;
import org.openbot.utils.MovingAverage;
import org.openbot.utils.PermissionUtils;
import org.openbot.vehicle.Control;

import android.content.pm.ActivityInfo;

import timber.log.Timber;

/**
 * Clase ObjectNavFragment
 * -----------------------
 * Este fragmento es el núcleo de la funcionalidad de "Seguimiento de Objetos".
 * Hereda de CameraFragment para tener acceso directo al flujo de video.
 *
 * Responsabilidades:
 * 1. Inicializar la Red Neuronal (TensorFlow Lite).
 * 2. Procesar cada cuadro (frame) de la cámara.
 * 3. Detectar objetos específicos (seleccionados por el usuario).
 * 4. Calcular la posición del objeto y enviar comandos al robot (Vehicle) para seguirlo.
 * 5. Gestionar la Interfaz de Usuario (UI) para configuración (Modelo, Confianza, Objeto).
 */
public class ObjectNavFragment extends CameraFragment {
  private FragmentObjectNavBinding binding;
  private Handler handler;
  private HandlerThread handlerThread; // Hilo secundario para no bloquear la UI con la IA

  private boolean computingNetwork = false; // Bandera para evitar saturar la IA con demasiados cuadros
  public static float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f; // Umbral mínimo de confianza (50%)

  private static final float TEXT_SIZE_DIP = 10;

  private Detector detector; // El objeto que ejecuta la detección TFLite

  private boolean mirrorControl;
  private Matrix frameToCropTransform;
  private Bitmap croppedBitmap;
  private int sensorOrientation;
  private Bitmap cropCopyBitmap;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker; // Clase auxiliar para dibujar los cuadros y calcular centros

  private Model model; // Modelo de IA cargado (ej. MobileNet, YOLO)
  private Network.Device device = Network.Device.CPU; // Dispositivo de procesamiento (CPU, GPU, NNAPI)
  private int numThreads = -1;
  private String classType = "person"; // Objeto a buscar por defecto

  private long lastProcessingTimeMs = -1;
  private long frameNum = 0;

  private final boolean isBenchmarkMode = false;
  private long processedFrames = 0;
  private final int movingAvgSize = 100;
  private MovingAverage movingAvgProcessingTimeMs = new MovingAverage(movingAvgSize);

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override
  public View onCreateView(
          @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentObjectNavBinding.inflate(inflater, container, false);
    return inflateFragment(binding, inflater, container);
  }

  /**
   * Configuración inicial de la UI y Listeners.
   * Se ejecuta cuando la vista del fragmento se ha creado.
   */
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // Configura el texto inicial de confianza
    binding.confidenceValue.setText((int) (MINIMUM_CONFIDENCE_TF_OD_API * 100) + "%");

    // Botones para ajustar el umbral de confianza (+/- 5%)
    binding.plusConfidence.setOnClickListener(
            v -> {
              String trimConfValue = binding.confidenceValue.getText().toString().trim();
              int confValue = Integer.parseInt(trimConfValue.substring(0, trimConfValue.length() - 1));
              if (confValue >= 95) return;
              confValue += 5;
              binding.confidenceValue.setText(confValue + "%");
              MINIMUM_CONFIDENCE_TF_OD_API = confValue / 100f;
            });

    binding.minusConfidence.setOnClickListener(
            v -> {
              String trimConfValue = binding.confidenceValue.getText().toString().trim();
              int confValue = Integer.parseInt(trimConfValue.substring(0, trimConfValue.length() - 1));
              if (confValue <= 5) return;
              confValue -= 5;
              binding.confidenceValue.setText(confValue + "%");
              MINIMUM_CONFIDENCE_TF_OD_API = confValue / 100f;
            });

    binding.controllerContainer.speedInfo.setText(getString(R.string.speedInfo, "---,---"));

    // Mostrar el toggle correcto según el tipo de conexión (USB o Bluetooth)
    if (vehicle.getConnectionType().equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
      binding.bleToggle.setVisibility(View.GONE);
    } else if (vehicle.getConnectionType().equals("Bluetooth")) {
      binding.bleToggle.setVisibility(View.VISIBLE);
      binding.usbToggle.setVisibility(View.GONE);
    }

    // Configura el Spinner para seleccionar el TIPO DE OBJETO a seguir (Persona, Celular, etc.)
    classType = preferencesManager.getObjectType();
    binding.classType.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
              @Override
              public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                classType = parent.getItemAtPosition(position).toString();
                preferencesManager.setObjectType(classType);
              }

              @Override
              public void onNothingSelected(AdapterView<?> parent) {}
            });

    // Configuración de hardware de IA (Threads, Dispositivo)
    binding.deviceSpinner.setSelection(preferencesManager.getDevice());
    setNumThreads(preferencesManager.getNumThreads());
    binding.threads.setText(String.valueOf(getNumThreads()));

    binding.cameraToggle.setOnClickListener(v -> toggleCamera());

    // Carga la lista de modelos disponibles
    List<String> models =
            getModelNames(f -> f.type.equals(Model.TYPE.DETECTOR) && f.pathType != Model.PATH_TYPE.URL);
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getObjectNavModel());

    setAnalyserResolution(Enums.Preview.HD.getValue());

    // Listener para cambio de Dispositivo (CPU/GPU)
    binding.deviceSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
              @Override
              public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                setDevice(Network.Device.valueOf(selected.toUpperCase()));
              }

              @Override
              public void onNothingSelected(AdapterView<?> parent) {}
            });

    // Control de Hilos (Threads) para la CPU
    binding.plus.setOnClickListener(
            v -> {
              String threads = binding.threads.getText().toString().trim();
              int numThreads = Integer.parseInt(threads);
              if (numThreads >= 9) return;
              setNumThreads(++numThreads);
              binding.threads.setText(String.valueOf(numThreads));
            });

    binding.minus.setOnClickListener(
            v -> {
              String threads = binding.threads.getText().toString().trim();
              int numThreads = Integer.parseInt(threads);
              if (numThreads == 1) return;
              setNumThreads(--numThreads);
              binding.threads.setText(String.valueOf(numThreads));
            });

    // Expande el panel de configuración inferior por defecto
    BottomSheetBehavior.from(binding.aiBottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);

    // Observadores de estado de conexión
    mViewModel.getUsbStatus().observe(getViewLifecycleOwner(), status -> binding.usbToggle.setChecked(status));

    binding.usbToggle.setChecked(vehicle.usbEstaConectada());
    binding.bleToggle.setChecked(vehicle.bleConnected());

    // Navegación a configuraciones de conexión
    binding.usbToggle.setOnClickListener(
            v -> {
              binding.usbToggle.setChecked(vehicle.usbEstaConectada());
              Navigation.findNavController(requireView()).navigate(R.id.open_usb_fragment);
            });

    binding.bleToggle.setOnClickListener(
            v -> {
              binding.bleToggle.setChecked(vehicle.bleConnected());
              Navigation.findNavController(requireView()).navigate(R.id.open_bluetooth_fragment);
            });

    setSpeedMode(Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()));
    setControlMode(Enums.ControlMode.getByID(preferencesManager.getControlMode()));
    setDriveMode(Enums.DriveMode.getByID(preferencesManager.getDriveMode()));

    // Listeners para botones de control manual (si se usan)
    binding.controllerContainer.controlMode.setOnClickListener(
            v -> {
              Enums.ControlMode controlMode =
                      Enums.ControlMode.getByID(preferencesManager.getControlMode());
              if (controlMode != null) setControlMode(Enums.switchControlMode(controlMode));
            });
    binding.controllerContainer.driveMode.setOnClickListener(
            v -> setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode())));

    binding.controllerContainer.speedMode.setOnClickListener(
            v ->
                    setSpeedMode(
                            Enums.toggleSpeed(
                                    Enums.Direction.CYCLIC.getValue(),
                                    Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()))));

    // INTERRUPTOR MAESTRO "AUTO": Activa/Desactiva el cerebro del robot
    binding.autoSwitch.setOnClickListener(v -> setNetworkEnabled(binding.autoSwitch.isChecked()));

    binding.dynamicSpeed.setChecked(preferencesManager.getDynamicSpeed());
    binding.dynamicSpeed.setOnClickListener(
            v -> {
              preferencesManager.setDynamicSpeed(binding.dynamicSpeed.isChecked());
              tracker.setDynamicSpeed(preferencesManager.getDynamicSpeed());
            });
  }

  private void mirrorControl() {
    requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    mirrorControl = !mirrorControl;
  }

  /**
   * Prepara los bitmaps y matrices necesarios para recortar y rotar la imagen de la cámara
   * antes de enviarla a la red neuronal.
   */
  private void updateCropImageInfo() {
    Timber.i("%s x %s",getPreviewSize().getWidth(), getPreviewSize().getHeight());

    frameToCropTransform = null;

    // Calcula la rotación necesaria para que la imagen quede "de pie" para la IA.
    // getScreenOrientation devuelve 0, 90, 180 o 270 según cómo sostengas el celular.
    // Esto es crucial para que funcione en horizontal y vertical.
    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());

    final float textSizePx =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    BorderedText borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(requireContext());
    tracker.setDynamicSpeed(preferencesManager.getDynamicSpeed());

    // Reinicia la red neuronal con la nueva configuración
    recreateNetwork(getModel(), getDevice(), getNumThreads());
    if (detector == null) {
      Timber.e("No network on preview!");
      return;
    }

    // Configura el callback de dibujo.
    // Aquí es donde ocurre la MAGIA DEL PANTILT.
    binding.trackingOverlay.addCallback(
            canvas -> {
              // 1. Dibuja los cuadros, la mira y el centroide en la pantalla.
              tracker.draw(canvas);

              // 2. Obtiene las coordenadas del objeto detectado desde el Tracker.
              // 3. Envía estas coordenadas al Vehicle para que mueva el Pan-Tilt.
              vehicle.receiveCenterOfTrackedObject(tracker.getCenterOfTrackedObject(), canvas.getWidth(), canvas.getHeight());

              tracker.clearTrackedObjects();
            });

    tracker.setFrameConfiguration(
            getMaxAnalyseImageSize().getWidth(),
            getMaxAnalyseImageSize().getHeight(),
            sensorOrientation);
  }

  /**
   * Se llama cuando cambia la configuración (modelo, dispositivo, hilos).
   */
  protected void onInferenceConfigurationChanged() {
    computingNetwork = false;
    if (croppedBitmap == null) {
      return;
    }
    final Network.Device device = getDevice();
    final Model model = getModel();
    final int numThreads = getNumThreads();
    runInBackground(() -> recreateNetwork(model, device, numThreads));
  }

  /**
   * Crea o recrea la instancia del Detector TFLite.
   */
  private void recreateNetwork(Model model, Network.Device device, int numThreads) {
    resetFpsUi();
    if (model == null) return;
    tracker.clearTrackedObjects();
    if (detector != null) {
      Timber.d("Closing detector.");
      detector.close();
      detector = null;
    }

    try {
      Timber.d("Creating detector (model=%s, device=%s, numThreads=%d)", model, device, numThreads);
      detector = Detector.create(requireActivity(), model, device, numThreads);

      assert detector != null;
      // Bitmap donde se copiará la imagen de la cámara redimensionada para la IA
      croppedBitmap =
              Bitmap.createBitmap(
                      detector.getImageSizeX(), detector.getImageSizeY(), Bitmap.Config.ARGB_8888);

      // Matriz de transformación (Escala y Rotación)
      frameToCropTransform =
              ImageUtils.getTransformationMatrix(
                      getMaxAnalyseImageSize().getWidth(),
                      getMaxAnalyseImageSize().getHeight(),
                      croppedBitmap.getWidth(),
                      croppedBitmap.getHeight(),
                      sensorOrientation,
                      detector.getCropRect(),
                      detector.getMaintainAspect());

      cropToFrameTransform = new Matrix();
      frameToCropTransform.invert(cropToFrameTransform);

      // Actualizar UI con info del modelo
      requireActivity()
              .runOnUiThread(
                      () -> {
                        ArrayAdapter<String> adapter =
                                new ArrayAdapter<>(
                                        getContext(),
                                        android.R.layout.simple_dropdown_item_1line,
                                        detector.getLabels());
                        binding.classType.setAdapter(adapter);
                        binding.classType.setSelection(
                                detector.getLabels().indexOf(preferencesManager.getObjectType()));
                        binding.inputResolution.setText(
                                String.format(
                                        Locale.getDefault(),
                                        "%dx%d",
                                        detector.getImageSizeX(),
                                        detector.getImageSizeY()));
                      });

    } catch (IllegalArgumentException | IOException e) {
      String msg = "Failed to create network.";
      Timber.e(e, msg);
      requireActivity()
              .runOnUiThread(
                      () ->
                              Toast.makeText(
                                              requireContext().getApplicationContext(),
                                              e.getMessage(),
                                              Toast.LENGTH_LONG)
                                      .show());
    }
  }

  @Override
  public synchronized void onResume() {
    croppedBitmap = null;
    tracker = null;
    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    binding.bleToggle.setChecked(vehicle.bleConnected());
    super.onResume();
  }

  @Override
  public synchronized void onPause() {
    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
    super.onPause();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        binding.controllerContainer.controlInfo.setText(
                String.format(Locale.US, "%.0f,%.0f"));
        break;

      case Constants.CMD_NETWORK:
        setNetworkEnabledWithAudio(!binding.autoSwitch.isChecked());
        break;
    }
  }

  @Override
  protected void processUSBData(String data) {
  }

  private void setNetworkEnabledWithAudio(boolean b) {
    setNetworkEnabled(b);

    if (b) audioPlayer.play(voice, "network_enabled.mp3");
    else audioPlayer.playDriveMode(voice, vehicle.getDriveMode());
  }

  /**
   * Habilita o deshabilita el modo autónomo (detección y movimiento).
   * Bloquea los controles manuales cuando está activo.
   */
  private void setNetworkEnabled(boolean b) {
    binding.autoSwitch.setChecked(b);

    binding.controllerContainer.controlMode.setEnabled(!b);
    binding.controllerContainer.driveMode.setEnabled(!b);
    binding.controllerContainer.speedMode.setEnabled(!b);

    binding.controllerContainer.controlMode.setAlpha(b ? 0.5f : 1f);
    binding.controllerContainer.driveMode.setAlpha(b ? 0.5f : 1f);
    binding.controllerContainer.speedMode.setAlpha(b ? 0.5f : 1f);

    resetFpsUi();
  }

  /**
   * BUCLE PRINCIPAL DE PROCESAMIENTO (Callback de Cámara).
   * Se ejecuta cada vez que llega una nueva imagen de la cámara.
   */
  @SuppressLint("SuspiciousIndentation")
  @Override
  protected void processFrame(Bitmap bitmap, ImageProxy image) {
    if (tracker == null) updateCropImageInfo();

    ++frameNum;

    // Solo procesar si el switch "Auto" está encendido
    if (binding != null && binding.autoSwitch.isChecked()) {
      if (computingNetwork) {
        return; // Si la IA sigue ocupada con el frame anterior, saltamos este
      }

      computingNetwork = true;

      // Ejecutar detección en hilo secundario (Background)
      runInBackground(
              () -> {
                // 1. Recortar y escalar imagen para la IA
                final Canvas canvas = new Canvas(croppedBitmap);
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                  canvas.drawBitmap(
                          CameraUtils.flipBitmapHorizontal(bitmap), frameToCropTransform, null);
                } else {
                  canvas.drawBitmap(bitmap, frameToCropTransform, null);
                }

                // 2. Ejecutar Inferencia (Detección)
                if (detector != null) {
                  final long startTime = SystemClock.elapsedRealtime();
                  final List<Detector.Recognition> results =
                          detector.recognizeImage(croppedBitmap, classType); // Busca solo el tipo seleccionado
                  lastProcessingTimeMs = SystemClock.elapsedRealtime() - startTime;

                  cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                  final Canvas canvas1 = new Canvas(cropCopyBitmap);
                  final Paint paint = new Paint();
                  paint.setColor(Color.RED);
                  paint.setStyle(Paint.Style.STROKE);
                  paint.setStrokeWidth(2.0f);

                  final List<Detector.Recognition> mappedRecognitions = new LinkedList<>();

                  // 3. Filtrar resultados por confianza mínima y dibujarlos
                  for (final Detector.Recognition result : results) {
                    final RectF location = result.getLocation();
                    if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API) {
                      canvas1.drawRect(location, paint);
                      // Mapear coordenadas de vuelta al tamaño original de pantalla
                      cropToFrameTransform.mapRect(location);
                      result.setLocation(location);
                      mappedRecognitions.add(result);
                    }
                  }

                  // 4. Actualizar el Tracker con los nuevos resultados
                  // Esto disparará el callback 'trackingOverlay' definido en updateCropImageInfo
                  // que llamará a vehicle.receiveCenterOfTrackedObject()
                  tracker.trackResults(mappedRecognitions, frameNum);

                  // 5. IMPORTANTE: Forzamos velocidad de ruedas a CERO
                  // para que el robot no persiga físicamente al objeto, solo use el Pan-Tilt.
                  vehicle.setControl(0, 0);

                  // Solicitar redibujado de la capa de superposición
                  binding.trackingOverlay.postInvalidate();
                }

                computingNetwork = false;
              });

      // Actualizar contador de FPS
      if (lastProcessingTimeMs > 0) {
        if (isBenchmarkMode) {
          double avgProcessingTimeMs = movingAvgProcessingTimeMs.next(lastProcessingTimeMs);
          processedFrames += 1;
          if (processedFrames >= movingAvgSize) updateFpsUi(avgProcessingTimeMs);
        } else updateFpsUi(lastProcessingTimeMs);
      }
    }
    tracker.clearTrackedObjects();
  }

  private void updateFpsUi(double processingTimeMs) {
    requireActivity()
            .runOnUiThread(
                    () ->
                            binding.inferenceInfo.setText(
                                    String.format(Locale.US, "%.1f fps", 1000.f / processingTimeMs)));
  }

  private void resetFpsUi() {
    processedFrames = 0;
    movingAvgProcessingTimeMs = new MovingAverage(movingAvgSize);
    requireActivity().runOnUiThread(() -> binding.inferenceInfo.setText(R.string.time_fps));
  }

  protected void handleDriveCommand(Control control) {
  }

  protected Model getModel() {
    return model;
  }

  @Override
  protected void setModel(Model model) {
    if (this.model != model) {
      Timber.d("Updating  model: %s", model);
      this.model = model;
      preferencesManager.setObjectNavModel(model.name);
      onInferenceConfigurationChanged();
    }
  }

  protected Network.Device getDevice() {
    return device;
  }

  private void setDevice(Network.Device device) {
    if (this.device != device) {
      Timber.d("Updating  device: %s", device);
      this.device = device;
      final boolean threadsEnabled = device == Network.Device.CPU;
      binding.plus.setEnabled(threadsEnabled);
      binding.minus.setEnabled(threadsEnabled);
      binding.threads.setText(threadsEnabled ? String.valueOf(numThreads) : "N/A");
      if (threadsEnabled) binding.threads.setTextColor(Color.BLACK);
      else binding.threads.setTextColor(Color.GRAY);
      preferencesManager.setDevice(device.ordinal());
      onInferenceConfigurationChanged();
    }
  }

  protected int getNumThreads() {
    return numThreads;
  }

  private void setNumThreads(int numThreads) {
    if (this.numThreads != numThreads) {
      Timber.d("Updating  numThreads: %s", numThreads);
      this.numThreads = numThreads;
      preferencesManager.setNumThreads(numThreads);
      onInferenceConfigurationChanged();
    }
  }

  private String[] getModelFiles() {
    return requireActivity().getFilesDir().list((dir1, name) -> name.endsWith(".tflite"));
  }

  private void setSpeedMode(Enums.SpeedMode speedMode) {
    if (speedMode != null) {
      switch (speedMode) {
        case SLOW:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_low);
          break;
        case NORMAL:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_medium);
          break;
        case FAST:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_high);
          break;
      }

      Timber.d("Updating  controlSpeed: %s", speedMode);
      preferencesManager.setSpeedMode(speedMode.getValue());
    }
  }

  private void setControlMode(Enums.ControlMode controlMode) {
    if (controlMode != null) {
      switch (controlMode) {
        case GAMEPAD:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_controller);
          disconnectPhoneController();
          break;
        case PHONE:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_phone);
          if (!PermissionUtils.hasControllerPermissions(requireActivity()))
            requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
          else connectPhoneController();
          break;
        case WEBSERVER:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_server);
          if (!PermissionUtils.hasControllerPermissions(requireActivity()))
            requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
          else connectWebController();
          break;
      }
      Timber.d("Updating  controlMode: %s", controlMode);
      preferencesManager.setControlMode(controlMode.getValue());
    }
  }

  protected void setDriveMode(Enums.DriveMode driveMode) {
    if (driveMode != null) {
      switch (driveMode) {
        case DUAL:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_dual);
          break;
        case GAME:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_game);
          break;
        case JOYSTICK:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_joystick);
          break;
      }

      Timber.d("Updating  driveMode: %s", driveMode);
      vehicle.setDriveMode(driveMode);
      preferencesManager.setDriveMode(driveMode.getValue());
    }
  }

  private void connectPhoneController() {
    phoneController.connect(requireContext());
    Enums.DriveMode oldDriveMode = currentDriveMode;
    // Currently only dual drive mode supported
    setDriveMode(Enums.DriveMode.DUAL);
    binding.controllerContainer.driveMode.setAlpha(0.5f);
    binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(oldDriveMode.getValue());
  }

  private void connectWebController() {
    phoneController.connectWebServer();
    Enums.DriveMode oldDriveMode = currentDriveMode;
    setDriveMode(Enums.DriveMode.GAME);
    binding.controllerContainer.driveMode.setAlpha(0.5f);
    binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(oldDriveMode.getValue());
  }

  private void disconnectPhoneController() {
    phoneController.disconnect();
    setDriveMode(Enums.DriveMode.getByID(preferencesManager.getDriveMode()));
    binding.controllerContainer.driveMode.setEnabled(true);
    binding.controllerContainer.driveMode.setAlpha(1.0f);
  }
}