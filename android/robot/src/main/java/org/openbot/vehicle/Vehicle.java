package org.openbot.vehicle;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import org.openbot.env.GameController;
import org.openbot.env.SensorReading;
import org.openbot.main.CommonRecyclerViewAdapter;
import org.openbot.main.ScanDeviceAdapter;
import org.openbot.utils.Enums;

public class Vehicle {

    private final Noise noise = new Noise(1000, 2000, 5000);
    private boolean noiseEnabled = false;

    private int indicator = 0;
    private int speedMultiplier = 192;
    private Control control = new Control(0, 0);

    // Lecturas de sensores
    private final SensorReading batteryVoltage = new SensorReading();
    private final SensorReading leftWheelRpm = new SensorReading();
    private final SensorReading rightWheelRpm = new SensorReading();
    private final SensorReading sonarReading = new SensorReading();

    // Configuración de batería
    private float minMotorVoltage = 2.5f;
    private float lowBatteryVoltage = 9.0f;
    private float maxBatteryVoltage = 12.6f;

    private UsbConnection conexionUsb;
    protected boolean usbConectada;
    private final Context context;
    private final int baudRate;

    // Características del vehículo
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

    // --- BLUETOOTH NATIVO ---
    private BluetoothManager bluetoothManager;
    private BluetoothDevice connectedDevice;

    SharedPreferences sharedPreferences;
    public String connectionType;

    // --- SISTEMA PAN-TILT ---
    private int currentPan = 0;
    private int currentTilt = 0;
    private long ultimoPanTiltTiempo = 0;
    private static final int PANTILT_INTERVAL_MS = 40;

    // PID Controllers
    private PIDControlador panPid = new PIDControlador(0.025f, 0.005f, 0.1f);
    private PIDControlador tiltPid = new PIDControlador(0.025f, 0.005f, 0.1f);

    private int maxPanStep = 3;
    private int maxTiltStep = 3;

    private class PIDControlador {
        private float kp, ki, kd;
        private float errorPrevio = 0;
        private float integral = 0;
        private float integralMaxima = 200;

        public PIDControlador(float kp, float ki, float kd) {
            this.kp = kp;
            this.ki = ki;
            this.kd = kd;
        }

        public float calculate(float setpoint, float actual) {
            float error = actual - setpoint;
            if (Math.abs(error) < 10) error = 0;
            integral += error;
            if (integral > integralMaxima) integral = integralMaxima;
            else if (integral < -integralMaxima) integral = -integralMaxima;
            float derivative = error - errorPrevio;
            float output = (kp * error) + (ki * integral) + (kd * derivative);
            errorPrevio = error;
            return output;
        }

        public void reset() {
            errorPrevio = 0;
            integral = 0;
        }
    }

    public Vehicle(Context context, int baudRate) {
        this.context = context;
        this.baudRate = baudRate;
        gameController = new GameController(driveMode);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        connectionType = getConnectionPreferences("connection_type", "USB");
        initBle();
    }

    // --- GETTERS Y SETTERS BÁSICOS (Mantenidos) ---
    // (Omitidos para brevedad, son los mismos de siempre)
    public float getMinMotorVoltage() { return minMotorVoltage; }
    public void setMinMotorVoltage(float minMotorVoltage) { this.minMotorVoltage = minMotorVoltage; }
    public float getLowBatteryVoltage() { return lowBatteryVoltage; }
    public void setLowBatteryVoltage(float lowBatteryVoltage) { this.lowBatteryVoltage = lowBatteryVoltage; }
    public float getMaxBatteryVoltage() { return maxBatteryVoltage; }
    public void setMaxBatteryVoltage(float maxBatteryVoltage) { this.maxBatteryVoltage = maxBatteryVoltage; }
    public boolean isReady() { return isReady; }
    public void setReady(boolean ready) { isReady = ready; }
    public boolean isHasVoltageDivider() { return hasVoltageDivider; }
    public void setHasVoltageDivider(boolean hasVoltageDivider) { this.hasVoltageDivider = hasVoltageDivider; }
    public boolean isHasIndicators() { return hasIndicators; }
    public void setHasIndicators(boolean hasIndicators) { this.hasIndicators = hasIndicators; }
    public boolean isHasSonar() { return hasSonar; }
    public void setHasSonar(boolean hasSonar) { this.hasSonar = hasSonar; }
    public boolean isHasBumpSensor() { return hasBumpSensor; }
    public void setHasBumpSensor(boolean hasBumpSensor) { this.hasBumpSensor = hasBumpSensor; }
    public boolean isHasWheelOdometryFront() { return hasWheelOdometryFront; }
    public void setHasWheelOdometryFront(boolean hasWheelOdometryFront) { this.hasWheelOdometryFront = hasWheelOdometryFront; }
    public boolean isHasWheelOdometryBack() { return hasWheelOdometryBack; }
    public void setHasWheelOdometryBack(boolean hasWheelOdometryBack) { this.hasWheelOdometryBack = hasWheelOdometryBack; }
    public boolean isHasLedsFront() { return hasLedsFront; }
    public void setHasLedsFront(boolean hasLedsFront) { this.hasLedsFront = hasLedsFront; }
    public boolean isHasLedsBack() { return hasLedsBack; }
    public void setHasLedsBack(boolean hasLedsBack) { this.hasLedsBack = hasLedsBack; }
    public boolean isHasLedsStatus() { return hasLedsStatus; }
    public void setHasLedsStatus(boolean hasLedsStatus) { this.hasLedsStatus = hasLedsStatus; }
    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }

    // --- LÓGICA DE VEHÍCULO ---

    public void requestVehicleConfig() { }

    public void processVehicleConfig(String message) {
        setVehicleType(message.split(":")[0]);
        if (message.contains(":v:")) { setHasVoltageDivider(true); setVoltageFrequency(250); }
        if (message.contains(":i:")) { setHasIndicators(true); }
        if (message.contains(":s:")) { setHasSonar(true); setSonarFrequency(100); }
        if (message.contains(":b:")) { setHasBumpSensor(true); }
        if (message.contains(":wf:")) { setHasWheelOdometryFront(true); setWheelOdometryFrequency(500); }
        if (message.contains(":wb:")) { setHasWheelOdometryBack(true); setWheelOdometryFrequency(500); }
        if (message.contains(":lf:")) { setHasLedsFront(true); }
        if (message.contains(":lb:")) { setHasLedsBack(true); }
        if (message.contains(":ls:")) { setHasLedsStatus(true); }
    }

    protected Enums.DriveMode driveMode = Enums.DriveMode.GAME;
    private final GameController gameController;
    private Timer heartbeatTimer;

    public float getBatteryVoltage() { return batteryVoltage.getReading(); }

    public int getBatteryPercentage() {
        return (int) ((batteryVoltage.getReading() - lowBatteryVoltage) * 100 / (maxBatteryVoltage - lowBatteryVoltage));
    }

    public void setBatteryVoltage(float batteryVoltage) { this.batteryVoltage.setReading(batteryVoltage); }
    public float getLeftWheelRpm() { return leftWheelRpm.getReading(); }
    public void setLeftWheelRpm(float leftWheelRpm) { this.leftWheelRpm.setReading(leftWheelRpm); }
    public float getRightWheelRpm() { return rightWheelRpm.getReading(); }
    public void setRightWheelRpm(float rightWheelRpm) { this.rightWheelRpm.setReading(rightWheelRpm); }

    public float getRotation() {
        float rotation = (getLeftSpeed() - getRightSpeed()) * 180 / (getLeftSpeed() + getRightSpeed());
        if (Float.isNaN(rotation) || Float.isInfinite(rotation)) rotation = 0f;
        return rotation;
    }

    public int getSpeedPercent() {
        float throttle = (getLeftSpeed() + getRightSpeed()) / 2;
        return Math.abs((int) (throttle * 100 / 255));
    }

    public String getDriveGear() {
        float throttle = (getLeftSpeed() + getRightSpeed()) / 2;
        if (throttle > 0) return "D";
        if (throttle < 0) return "R";
        return "P";
    }

    public float getSonarReading() { return sonarReading.getReading(); }
    public void setSonarReading(float sonarReading) { this.sonarReading.setReading(sonarReading); }
    public Control getControl() { return control; }
    public void setControl(Control control) { this.control = control; }
    public void setControl(float left, float right) { this.control = new Control(left, right); }

    private Timer noiseTimer;

    public void toggleNoise() { if (noiseEnabled) stopNoise(); else startNoise(); }
    public boolean isNoiseEnabled() { return noiseEnabled; }
    public void setDriveMode(Enums.DriveMode driveMode) { this.driveMode = driveMode; gameController.setDriveMode(driveMode); }
    public Enums.DriveMode getDriveMode() { return driveMode; }
    public GameController getGameController() { return gameController; }

    private class NoiseTask extends TimerTask {
        @Override
        public void run() { noise.update(); }
    }

    public void startNoise() {
        noiseTimer = new Timer();
        NoiseTask noiseTask = new NoiseTask();
        noiseTimer.schedule(noiseTask, 0, 50);
        noiseEnabled = true;
    }

    public void stopNoise() {
        noiseEnabled = false;
        noiseTimer.cancel();
    }

    public int getSpeedMultiplier() { return speedMultiplier; }
    public void setSpeedMultiplier(int speedMultiplier) { this.speedMultiplier = speedMultiplier; }
    public int getIndicator() { return indicator; }
    public void setIndicator(int indicator) { this.indicator = indicator; }

    public UsbConnection getConexionUsb() { return conexionUsb; }

    // --- CONEXIÓN USB ---

    public void usbConectada() {
        if (conexionUsb == null) conexionUsb = new UsbConnection(context, baudRate);
        usbConectada = conexionUsb.startUsbConnection();
        if (usbConectada) {
            if (heartbeatTimer == null) startHeartbeat();
            reiniciaPanTilt();
        }
    }

    public void usbDesconectada() {
        if (conexionUsb != null) {
            stopBot();
            stopHeartbeat();
            conexionUsb.stopUsbConnection();
            conexionUsb = null;
            usbConectada = false;
        }
    }

    public boolean usbEstaConectada() { return usbConectada; }

    // --- MÉTODO HÍBRIDO DE ENVÍO ---
    private void mandarBytesAlDispositivo(byte[] message) {
        if (getConnectionType().equals("USB") && conexionUsb != null) {
            conexionUsb.send(message);
        } else if(getConnectionType().equals("Bluetooth")
                && bluetoothManager != null
                && bluetoothManager.isConnected()) {
            bluetoothManager.write(message);
        }
    }

    public float getLeftSpeed() { return control.getLeft() * speedMultiplier; }
    public float getRightSpeed() { return control.getRight() * speedMultiplier; }

    public void sendLightIntensity(float frontPercent, float backPercent) { }

    public void sendCoordinatesToRobot(int coordX, int coordY) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(coordX);
        buffer.putInt(coordY);
        byte[] byteArray = buffer.array();
        mandarBytesAlDispositivo(byteArray);
    }

    public void sendConteoPrueba() { }

    public void receiveCenterOfTrackedObject(Point centerPoint, int frameWidth, int frameHeight) {
        if (centerPoint != null) {
            trackObject(centerPoint, frameWidth, frameHeight);
        }
    }

    public void sendControl() { }
    protected void sendMessageFrank(String message) { }
    protected void sendHeartbeat(int timeout_ms) { }
    protected void setSonarFrequency(int interval_ms) { }
    protected void setVoltageFrequency(int interval_ms) { }
    protected void setWheelOdometryFrequency(int interval_ms) { }

    // --- LÓGICA PAN-TILT ---

    public void reiniciaPanTilt() {
        currentPan = 0;
        currentTilt = 0;
        panPid.reset();
        tiltPid.reset();
        String jsonCommand = String.format(Locale.US, "{\"T\":133,\"X\":%d,\"Y\":%d,\"SPD\":0,\"ACC\":0}\n", 0, 0);
        mandarBytesAlDispositivo(jsonCommand.getBytes(StandardCharsets.UTF_8));
    }

    public void trackObject(Point centerPoint, int frameWidth, int frameHeight) {
        if (centerPoint == null) return;
        int centerX = frameWidth / 2;
        int centerY = frameHeight / 2;

        float pidOutputX = panPid.calculate(centerX, centerPoint.x);
        int adjustmentX = Math.round(pidOutputX);

        if (adjustmentX > maxPanStep) adjustmentX = maxPanStep;
        else if (adjustmentX < -maxPanStep) adjustmentX = -maxPanStep;

        if (Math.abs(centerPoint.x - centerX) > 60) {
            currentPan += adjustmentX;
        }

        float pidOutputY = tiltPid.calculate(centerY, centerPoint.y);
        int adjustmentY = Math.round(pidOutputY);

        if (adjustmentY > maxTiltStep) adjustmentY = maxTiltStep;
        else if (adjustmentY < -maxTiltStep) adjustmentY = -maxTiltStep;

        if (Math.abs(centerPoint.y - centerY) > 60) {
            currentTilt -= adjustmentY;
        }

        currentPan = Math.max(-180, Math.min(180, currentPan));
        currentTilt = Math.max(-30, Math.min(90, currentTilt));

        mandarPanTilt(currentPan, currentTilt);
    }

    public void mandarPanTilt(int pan, int tilt) {
        long TiempoActual = System.currentTimeMillis();
        if (TiempoActual - ultimoPanTiltTiempo < PANTILT_INTERVAL_MS) { return; }
        ultimoPanTiltTiempo = TiempoActual;
        String jsonCommand = String.format(Locale.US, "{\"T\":133,\"X\":%d,\"Y\":%d,\"SPD\":0,\"ACC\":0}\n", pan, tilt);
        mandarBytesAlDispositivo(jsonCommand.getBytes(StandardCharsets.UTF_8));
    }

    private class HeartBeatTask extends TimerTask {
        @Override
        public void run() { }
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

    // --- GESTIÓN BLUETOOTH (Corregida) ---

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
        // AQUÍ YA NO NECESITAMOS LA ANOTACIÓN PORQUE BLUETOOTHMANAGER LO MANEJA
        if(bluetoothManager != null) bluetoothManager.startScan();
    }

    public void stopScan() {
        if(bluetoothManager != null) bluetoothManager.stopScan();
    }

    public List<BluetoothDevice> getDeviceList() {
        return bluetoothManager.deviceList;
    }

    public void setBleDevice(BluetoothDevice device) {
        this.connectedDevice = device;
    }

    public BluetoothDevice getBleDevice() {
        return this.connectedDevice;
    }

    public void toggleConnection(int position, BluetoothDevice device) {
        if (bluetoothManager.isConnected()) {
            bluetoothManager.disconnect();
            connectedDevice = null;
        } else {
            bluetoothManager.connect(device);
            connectedDevice = device;
        }
    }

    public void initBle() {
        if (bluetoothManager == null) {
            bluetoothManager = new BluetoothManager(context);
        }
    }

    private void sendStringToBle(String message) {
        bluetoothManager.write(message);
    }

    public boolean bleConnected() {
        return bluetoothManager != null && bluetoothManager.isConnected();
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