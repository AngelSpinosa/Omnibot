package org.openbot.vehicle;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.PointF;
import android.icu.text.SymbolTable;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.ficat.easyble.BleDevice;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Handler;

import org.openbot.env.GameController;
import org.openbot.env.SensorReading;
import org.openbot.main.CommonRecyclerViewAdapter;
import org.openbot.main.ScanDeviceAdapter;
import org.openbot.utils.Enums;

import timber.log.Timber;

public class Vehicle {

  private final Noise noise = new Noise(1000, 2000, 5000);
  private boolean noiseEnabled = false;

  private int indicator = 0;
  private int speedMultiplier = 192; // 128,192,255
  private Control control = new Control(0, 0);

  private final SensorReading batteryVoltage = new SensorReading();
  private final SensorReading leftWheelRpm = new SensorReading();
  private final SensorReading rightWheelRpm = new SensorReading();
  private final SensorReading sonarReading = new SensorReading();

  private float minMotorVoltage = 2.5f;
  private float lowBatteryVoltage = 9.0f;
  private float maxBatteryVoltage = 12.6f;

  private UsbConnection usbConnection;
  protected boolean usbConnected;
  private final Context context;
  private final int baudRate;

  private String vehicleType = "";
  private boolean hasVoltageDivider = false;
  private boolean hasIndicators = false;
  private boolean hasSonar = false;
  private boolean hasBumpSensor = false;
  private boolean hasWheelOdometryFront = false;
  private boolean hasWheelOdometryBack = false;
  private boolean hasLedsFront = false;
  private boolean hasLedsBack = false;
  private boolean hasLedsStatus = false;
  private boolean isReady = false;

  private Timer serialMessageTimer;

  private BluetoothManager bluetoothManager;
  SharedPreferences sharedPreferences;
  public String connectionType;

  // --- PANTILT: Variables y PID ---
  private int currentPan = 0;
  private int currentTilt = 0;
  private long lastPanTiltTime = 0;
  private static final int PANTILT_INTERVAL_MS = 40;

  // PID SUAVIZADO:
  // Kp bajado a 0.04 (Reacción más lenta/suave)
  // Ki bajado a 0.01 (Menos acumulación)
  // Kd subido a 0.05 (Más "freno" para evitar oscilaciones)
  private PIDController panPid = new PIDController(0.04f, 0.01f, 0.05f);

  // NUEVO: Velocidad Máxima (Grados por ciclo de 40ms)
  // 5 grados por ciclo = max 125 grados/segundo aprox. Suficiente para tracking suave.
  private int maxPanStep = 5;
  // ------------------------------------

  // --- CLASE INTERNA PID MEJORADA ---
  private class PIDController {
    private float kp, ki, kd;
    private float previousError = 0;
    private float integral = 0;
    // Limite para evitar que la integral crezca infinito (Anti-Windup)
    //reducción para limitar el movimiento
    private float maxIntegral = 500; // Reducido para evitar bloqueos largos

    public PIDController(float kp, float ki, float kd) {
      this.kp = kp;
      this.ki = ki;
      this.kd = kd;
    }

    public float calculate(float setpoint, float actual) {
      // Error = (Donde está el objeto) - (Donde quiero que esté/Centro)
      float error = actual - setpoint;

      // Integral (acumulativa) con Limite (Clamping)
      integral += error;
      if (integral > maxIntegral) integral = maxIntegral;
      else if (integral < -maxIntegral) integral = -maxIntegral;

      // Derivada
      float derivative = error - previousError;

      float output = (kp * error) + (ki * integral) + (kd * derivative);

      previousError = error;
      return output;
    }

    public void reset() {
      previousError = 0;
      integral = 0;
    }
  }
  // ---------------------------------------------------------

  public float getMinMotorVoltage() {
    return minMotorVoltage;
  }

  public void setMinMotorVoltage(float minMotorVoltage) {
    this.minMotorVoltage = minMotorVoltage;
  }

  public float getLowBatteryVoltage() {
    return lowBatteryVoltage;
  }

  public void setLowBatteryVoltage(float lowBatteryVoltage) {
    this.lowBatteryVoltage = lowBatteryVoltage;
  }

  public float getMaxBatteryVoltage() {
    return maxBatteryVoltage;
  }

  public void setMaxBatteryVoltage(float maxBatteryVoltage) {
    this.maxBatteryVoltage = maxBatteryVoltage;
  }

  public boolean isReady() {
    return isReady;
  }

  public void setReady(boolean ready) {
    isReady = ready;
  }

  public boolean isHasVoltageDivider() {
    return hasVoltageDivider;
  }

  public void setHasVoltageDivider(boolean hasVoltageDivider) {
    this.hasVoltageDivider = hasVoltageDivider;
  }

  public boolean isHasIndicators() {
    return hasIndicators;
  }

  public void setHasIndicators(boolean hasIndicators) {
    this.hasIndicators = hasIndicators;
  }

  public boolean isHasSonar() {
    return hasSonar;
  }

  public void setHasSonar(boolean hasSonar) {
    this.hasSonar = hasSonar;
  }

  public boolean isHasBumpSensor() {
    return hasBumpSensor;
  }

  public void setHasBumpSensor(boolean hasBumpSensor) {
    this.hasBumpSensor = hasBumpSensor;
  }

  public boolean isHasWheelOdometryFront() {
    return hasWheelOdometryFront;
  }

  public void setHasWheelOdometryFront(boolean hasWheelOdometryFront) {
    this.hasWheelOdometryFront = hasWheelOdometryFront;
  }

  public boolean isHasWheelOdometryBack() {
    return hasWheelOdometryBack;
  }

  public void setHasWheelOdometryBack(boolean hasWheelOdometryBack) {
    this.hasWheelOdometryBack = hasWheelOdometryBack;
  }

  public boolean isHasLedsFront() {
    return hasLedsFront;
  }

  public void setHasLedsFront(boolean hasLedsFront) {
    this.hasLedsFront = hasLedsFront;
  }

  public boolean isHasLedsBack() {
    return hasLedsBack;
  }

  public void setHasLedsBack(boolean hasLedsBack) {
    this.hasLedsBack = hasLedsBack;
  }

  public boolean isHasLedsStatus() {
    return hasLedsStatus;
  }

  public void setHasLedsStatus(boolean hasLedsStatus) {
    this.hasLedsStatus = hasLedsStatus;
  }

  public String getVehicleType() {
    return vehicleType;
  }

  public void setVehicleType(String vehicleType) {
    this.vehicleType = vehicleType;
  }

  public void requestVehicleConfig() {
    //sendStringToDevice(String.format(Locale.US, "f\n"));
  }

  public void processVehicleConfig(String message) {
    setVehicleType(message.split(":")[0]);

    if (message.contains(":v:")) {
      setHasVoltageDivider(true);
      setVoltageFrequency(250);
    }
    if (message.contains(":i:")) {
      setHasIndicators(true);
    }
    if (message.contains(":s:")) {
      setHasSonar(true);
      setSonarFrequency(100);
    }
    if (message.contains(":b:")) {
      setHasBumpSensor(true);
    }
    if (message.contains(":wf:")) {
      setHasWheelOdometryFront(true);
      setWheelOdometryFrequency(500);
    }
    if (message.contains(":wb:")) {
      setHasWheelOdometryBack(true);
      setWheelOdometryFrequency(500);
    }
    if (message.contains(":lf:")) {
      setHasLedsFront(true);
    }
    if (message.contains(":lb:")) {
      setHasLedsBack(true);
    }
    if (message.contains(":ls:")) {
      setHasLedsStatus(true);
    }
  }

  protected Enums.DriveMode driveMode = Enums.DriveMode.GAME;
  private final GameController gameController;
  private Timer heartbeatTimer;

  public Vehicle(Context context, int baudRate) {
    this.context = context;
    this.baudRate = baudRate;
    gameController = new GameController(driveMode);
    sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    connectionType = getConnectionPreferences("connection_type", "USB");
  }

  public float getBatteryVoltage() {
    return batteryVoltage.getReading();
  }

  public int getBatteryPercentage() {
    return (int)
            ((batteryVoltage.getReading() - lowBatteryVoltage)
                    * 100
                    / (maxBatteryVoltage - lowBatteryVoltage));
  }

  public void setBatteryVoltage(float batteryVoltage) {
    this.batteryVoltage.setReading(batteryVoltage);
  }

  public float getLeftWheelRpm() {
    return leftWheelRpm.getReading();
  }

  public void setLeftWheelRpm(float leftWheelRpm) {
    this.leftWheelRpm.setReading(leftWheelRpm);
  }

  public float getRightWheelRpm() {
    return rightWheelRpm.getReading();
  }

  public void setRightWheelRpm(float rightWheelRpm) {
    this.rightWheelRpm.setReading(rightWheelRpm);
  }

  public float getRotation() {
    float rotation = (getLeftSpeed() - getRightSpeed()) * 180 / (getLeftSpeed() + getRightSpeed());
    if (Float.isNaN(rotation) || Float.isInfinite(rotation)) rotation = 0f;
    return rotation;
  }

  public int getSpeedPercent() {
    float throttle = (getLeftSpeed() + getRightSpeed()) / 2;
    return Math.abs((int) (throttle * 100 / 255)); // 255 is the max speed
  }

  public String getDriveGear() {
    float throttle = (getLeftSpeed() + getRightSpeed()) / 2;
    if (throttle > 0) return "D";
    if (throttle < 0) return "R";
    return "P";
  }

  public float getSonarReading() {
    return sonarReading.getReading();
  }

  public void setSonarReading(float sonarReading) {
    this.sonarReading.setReading(sonarReading);
  }

  public Control getControl() {
    return control;
  }

  public void setControl(Control control) {
    this.control = control;
    //sendControl();
  }

  public void setControl(float left, float right) {
    this.control = new Control(left, right);
    //sendControl();
  }

  private Timer noiseTimer;

  public void toggleNoise() {
    if (noiseEnabled) stopNoise();
    else startNoise();
  }

  public boolean isNoiseEnabled() {
    return noiseEnabled;
  }

  public void setDriveMode(Enums.DriveMode driveMode) {
    this.driveMode = driveMode;
    gameController.setDriveMode(driveMode);
  }

  public Enums.DriveMode getDriveMode() {
    return driveMode;
  }

  public GameController getGameController() {
    return gameController;
  }

  private class NoiseTask extends TimerTask {
    @Override
    public void run() {
      noise.update();
      //sendControl();
    }
  }

  public void startNoise() {
    noiseTimer = new Timer();
    NoiseTask noiseTask = new NoiseTask();
    noiseTimer.schedule(noiseTask, 0, 50);
    noiseEnabled = true;
    //sendControl();
  }

  public void stopNoise() {
    noiseEnabled = false;
    noiseTimer.cancel();
    //sendControl();
  }

  public int getSpeedMultiplier() {
    return speedMultiplier;
  }

  public void setSpeedMultiplier(int speedMultiplier) {
    this.speedMultiplier = speedMultiplier;
  }

  public int getIndicator() {
    return indicator;
  }

  public void setIndicator(int indicator) {
    this.indicator = indicator;
    switch (indicator) {
      case -1:
        //sendStringToDevice(String.format(Locale.US, "i1,0\n"));
        break;
      case 0:
        //sendStringToDevice(String.format(Locale.US, "i0,0\n"));
        break;
      case 1:
        //sendStringToDevice(String.format(Locale.US, "i0,1\n"));
        break;
    }
  }

  public UsbConnection getUsbConnection() {
    return usbConnection;
  }

  public void connectUsb() {
    if (usbConnection == null) usbConnection = new UsbConnection(context, baudRate);
    usbConnected = usbConnection.startUsbConnection();
    if (usbConnected) {
      if (heartbeatTimer == null) {
        startHeartbeat();
      }
      resetPanTilt();
    }
  }

  public void disconnectUsb() {
    if (usbConnection != null) {
      stopBot();
      stopHeartbeat();
      usbConnection.stopUsbConnection();
      usbConnection = null;
      usbConnected = false;
    }
  }

  public boolean isUsbConnected() {
    return usbConnected;
  }

  private void sendBytesToDevice(byte[] message) {
    if (getConnectionType().equals("USB") && usbConnection != null) {
      usbConnection.send(message);
    } else if(getConnectionType().equals("Bluetooth")
            && bluetoothManager != null
            && bluetoothManager.isBleConnected()) {
    }
  }

  public float getLeftSpeed() {
    return control.getLeft() * speedMultiplier;
  }

  public float getRightSpeed() {
    return control.getRight() * speedMultiplier;
  }

  public void sendLightIntensity(float frontPercent, float backPercent) {
    int front = (int) (frontPercent * 255.f);
    int back = (int) (backPercent * 255.f);
    //sendStringToDevice(String.format(Locale.US, "l%d,%d\n", front, back));
  }

  public void sendCoordinatesToRobot(int coordX, int coordY) {
    ByteBuffer buffer = ByteBuffer.allocate(8);
    buffer.putInt(coordX);
    buffer.putInt(coordY);
    byte[] byteArray = buffer.array();
    sendBytesToDevice(byteArray);
  }

  public void sendConteoPrueba() {
    for (int i = 1; i <= 180; i++) {
      String conteo = String.valueOf(i);
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  public void receiveCenterOfTrackedObject(Point centerPoint, int frameWidth, int frameHeight) {
    if (centerPoint != null) {
      trackObject(centerPoint, frameWidth, frameHeight);
    }
  }

  public void sendControl() {
    int left = (int) (getLeftSpeed());
    int right = (int) (getRightSpeed());
    if (noiseEnabled && noise.getDirection() < 0) {
      left = (int) ((control.getLeft() - noise.getValue()) * speedMultiplier);
    }
    if (noiseEnabled && noise.getDirection() > 0) {
      right = (int) ((control.getRight() - noise.getValue()) * speedMultiplier);
    }
    //sendStringToDevice(String.format(Locale.US, "c%d,%d\n", left, right));
  }

  protected void sendMessageFrank(String message) {
    //sendStringToDevice("f");
  }

  protected void sendHeartbeat(int timeout_ms) {
    //sendStringToDevice(String.format(Locale.getDefault(), "h%d\n", timeout_ms));
  }
  protected void setSonarFrequency(int interval_ms) {
    //sendStringToDevice(String.format(Locale.getDefault(), "s%d\n", interval_ms));
  }

  protected void setVoltageFrequency(int interval_ms) {
    //sendStringToDevice(String.format(Locale.getDefault(), "v%d\n", interval_ms));
  }

  protected void setWheelOdometryFrequency(int interval_ms) {
    //sendStringToDevice(String.format(Locale.getDefault(), "w%d\n", interval_ms));
  }

  // --- LOGICA PANTILT CON PID MEJORADA (SUAVIZADO) ---

  public void resetPanTilt() {
    currentPan = 0;
    currentTilt = 0;
    panPid.reset();
    String jsonCommand = String.format(Locale.US, "{\"T\":133,\"X\":%d,\"Y\":%d,\"SPD\":0,\"ACC\":0}\n", 0, 0);
    sendBytesToDevice(jsonCommand.getBytes(StandardCharsets.UTF_8));
  }

  public void trackObject(Point centerPoint, int frameWidth, int frameHeight) {
    if (centerPoint == null) return;

    int centerX = frameWidth / 2;
    // int centerY = frameHeight / 2;

    float pidOutput = panPid.calculate(centerX, centerPoint.x);

    int adjustment = Math.round(pidOutput);

    // --- NUEVO: Limitador de Velocidad (Slew Rate Limiter) ---
    // Evita saltos bruscos limitando cuántos grados puede cambiar en un ciclo
    if (adjustment > maxPanStep) adjustment = maxPanStep;
    else if (adjustment < -maxPanStep) adjustment = -maxPanStep;
    // ---------------------------------------------------------

    // Zona muerta
    if (Math.abs(centerPoint.x - centerX) > 40) {
      currentPan += adjustment;
    }

    // --- EJE Y (TILT) BLOQUEADO ---
    currentTilt = 0;

    // Limites de seguridad
    currentPan = Math.max(-180, Math.min(180, currentPan));
    currentTilt = Math.max(-30, Math.min(90, currentTilt));

    sendPanTilt(currentPan, currentTilt);
  }

  public void sendPanTilt(int pan, int tilt) {
    long currentTime = System.currentTimeMillis();

    if (currentTime - lastPanTiltTime < PANTILT_INTERVAL_MS) {
      return;
    }

    lastPanTiltTime = currentTime;

    String jsonCommand = String.format(Locale.US, "{\"T\":133,\"X\":%d,\"Y\":%d,\"SPD\":0,\"ACC\":0}\n", pan, tilt);
    sendBytesToDevice(jsonCommand.getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------


  private class HeartBeatTask extends TimerTask {

    @Override
    public void run() {
      //sendHeartbeat(750);
      // sendMessageFrank("f");
    }
  }

  public void startHeartbeat() {
    heartbeatTimer = new Timer();
    HeartBeatTask heartBeatTask = new HeartBeatTask();
    heartbeatTimer.schedule(heartBeatTask, 250, 250);
  }

  public void stopHeartbeat() {
    if(heartbeatTimer != null) {
      heartbeatTimer.cancel();
      heartbeatTimer.purge();
      heartbeatTimer = null;
    }
  }

  public void stopBot() {
    Control control = new Control(0, 0);
    setControl(control);
  }

  public ScanDeviceAdapter getBleAdapter() {
    return bluetoothManager.adapter;
  }

  public void setBleAdapter(
          ScanDeviceAdapter adapter,
          @NonNull CommonRecyclerViewAdapter.OnItemClickListener onItemClickListener) {
    bluetoothManager.adapter = adapter;
    bluetoothManager.adapter.setOnItemClickListener(onItemClickListener);
  }

  public void startScan() {
    bluetoothManager.startScan();
  }

  public void stopScan() {
    bluetoothManager.stopScan();
  }

  public List<BleDevice> getDeviceList() {
    return bluetoothManager.deviceList;
  }

  public void setBleDevice(BleDevice device) {
    bluetoothManager.bleDevice = device;
  }

  public BleDevice getBleDevice() {
    return bluetoothManager.bleDevice;
  }

  public void toggleConnection(int position, BleDevice device) {
    bluetoothManager.toggleConnection(position, device);
  }

  public void initBle() {
    bluetoothManager = new BluetoothManager(context);
  }

  private void sendStringToBle(String message) {
    bluetoothManager.write(message);
  }

  public boolean bleConnected() {
    return bluetoothManager.isBleConnected();
  }

  private void setConnectionPreferences(String name, String value) {
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putString(name, value);
    editor.apply();
  }

  private String getConnectionPreferences(String name, String defaultValue) {
    try {
      if (sharedPreferences != null) {
        return sharedPreferences.getString(name, defaultValue);
      } else return defaultValue;
    } catch (ClassCastException e) {
      return defaultValue;
    }
  }

  public String getConnectionType() {
    return getConnectionPreferences("connection_type", "USB");
  }
}