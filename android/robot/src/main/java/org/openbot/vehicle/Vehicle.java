package org.openbot.vehicle;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.ficat.easyble.BleDevice;

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

import timber.log.Timber;

/**
 * Clase Vehicle
 * -------------
 * Representa la entidad lógica del robot OpenBot.
 * * Responsabilidades:
 * 1. Gestionar el estado del robot (batería, sensores, velocidad).
 * 2. Controlar la comunicación con el hardware (USB, Bluetooth o WIFI).
 * 3. Implementar la lógica de control de movimiento (Drive Mode, Gamepad).
 * 4. Gestionar el sistema Pan-Tilt con control PID para seguimiento de objetos.
 */
public class Vehicle {

    private final Noise noise = new Noise(1000, 2000, 5000);
    private boolean noiseEnabled = false;

    private int indicator = 0;
    private int speedMultiplier = 192; // Multiplicador de velocidad (128, 192, 255)
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

    // --- CONEXIÓN USB ---
    private UsbConnection conexionUsb; // Objeto para comunicación USB
    protected boolean usbConectada;

    // --- NUEVO: CONEXIÓN WIFI ---
    private WifiConnection conexionWifi;
    protected boolean wifiConectada;
    private static final String WIFI_IP = "192.168.4.1"; // <-- Asegúrate de que esta sea la IP de tu placa ESP32
    private static final int WIFI_PORT = 12345; // <-- Puerto UDP configurado en tu placa
    // ----------------------------

    private final Context context;
    private final int baudRate;

    // Características del vehículo (detectadas automáticamente)
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

    // --- SISTEMA PAN-TILT (Variables de Estado y Control) ---

    // Posición lógica actual de los servos (en grados acumulados)
    private int currentPan = 0;
    private int currentTilt = 0;
    private long ultimoPanTiltTiempo = 0;
    private static final int PANTILT_INTERVAL_MS = 40; // Frecuencia de actualización (40ms = 25Hz)

    // CONTROLADORES PID (Proporcional-Integral-Derivativo)
    // Optimizados para movimientos suaves y estables.
    // Kp=0.025 (Suave), Ki=0.005 (Baja acumulación), Kd=0.1 (Freno fuerte)
    private PIDControlador panPid = new PIDControlador(0.025f, 0.005f, 0.1f);
    private PIDControlador tiltPid = new PIDControlador(0.025f, 0.005f, 0.1f);

    // Limitador de Velocidad (Slew Rate Limiter)
    // Máximo cambio de grados por ciclo para evitar sacudidas bruscas.
    private int maxPanStep = 3;
    private int maxTiltStep = 3;
    // --------------------------------------------------------

    /**
     * CLASE INTERNA PIDControlador
     * ---------------------------
     * Implementa el algoritmo de control PID con protección "Anti-Windup".
     * Calcula cuánto debe moverse el motor basándose en el error (distancia al objetivo).
     */
    private class PIDControlador {
        private float kp, ki, kd;
        private float errorPrevio = 0;
        private float integral = 0;
        // Limite para evitar que la integral crezca infinito si el robot se atora
        private float integralMaxima = 200;

        public PIDControlador(float kp, float ki, float kd) {
            this.kp = kp;
            this.ki = ki;
            this.kd = kd;
        }

        public float calculate(float setpoint, float actual) {
            // Error = Posición Actual - Objetivo
            float error = actual - setpoint;

            // Zona muerta interna: Ignorar errores minúsculos para no acumular integral
            if (Math.abs(error) < 10) error = 0;

            // Término Integral (Acumula errores pasados para corregir desviación constante)
            integral += error;
            // Clamping (Anti-Windup)
            if (integral > integralMaxima) integral = integralMaxima;
            else if (integral < -integralMaxima) integral = -integralMaxima;

            // Término Derivativo (Predice el futuro para frenar oscilaciones)
            float derivative = error - errorPrevio;

            // Salida final = P + I + D
            float output = (kp * error) + (ki * integral) + (kd * derivative);

            errorPrevio = error;
            return output;
        }

        // Reinicia la memoria del PID (útil al reconectar o cambiar de modo)
        public void reset() {
            errorPrevio = 0;
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

    /**
     * Procesa la cadena de configuración recibida del microcontrolador.
     * Activa/desactiva las banderas de características (sensores, leds, etc.).
     */
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
    }

    public void setControl(float left, float right) {
        this.control = new Control(left, right);
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
        }
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

    // --- MÉTODOS DE CONEXIÓN USB ---
    public UsbConnection getConexionUsb() {
        return conexionUsb;
    }

    public void usbConectada() {
        if (conexionUsb == null) conexionUsb = new UsbConnection(context, baudRate);
        usbConectada = conexionUsb.startUsbConnection();
        if (usbConectada) {
            if (heartbeatTimer == null) {
                startHeartbeat();
            }
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

    public boolean usbEstaConectada() {
        return usbConectada;
    }

    // --- NUEVO: MÉTODOS DE CONEXIÓN WIFI ---
    public WifiConnection getConexionWifi() {
        return conexionWifi;
    }

    public void conectarWifi() {
        if (conexionWifi == null) conexionWifi = new WifiConnection(context);
        conexionWifi.startWifiConnection(WIFI_IP, WIFI_PORT);
        wifiConectada = true; // UDP es sin conexión estable, asumimos que estamos listos para enviar datos
        if (heartbeatTimer == null) {
            startHeartbeat();
        }
        reiniciaPanTilt(); // Sincroniza hardware a 0,0 al conectar Wifi
    }

    public void desconectarWifi() {
        if (conexionWifi != null) {
            stopBot();
            stopHeartbeat();
            conexionWifi.stopWifiConnection();
            conexionWifi = null;
            wifiConectada = false;
        }
    }

    public boolean wifiEstaConectada() {
        return wifiConectada;
    }
    // ---------------------------------------

    /**
     * ENRUTADOR PRINCIPAL DE DATOS:
     * Envía bytes crudos al dispositivo conectado evaluando la preferencia actual (USB, WIFI, Bluetooth).
     */
    private void mandarBytesAlDispositivo(byte[] message) {
        String tipoConexion = getConnectionType();

        if (tipoConexion.equals("USB") && conexionUsb != null) {
            conexionUsb.send(message);
        } else if (tipoConexion.equals("WIFI") && conexionWifi != null) {
            // ENVÍO ENRUTADO A WIFI
            conexionWifi.send(message);
        } else if(tipoConexion.equals("Bluetooth")
                && bluetoothManager != null
                && bluetoothManager.isBleConnected()) {
            // bluetoothManager.write(message); o equivalente si se usara BLE
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
    }

    public void sendCoordinatesToRobot(int coordX, int coordY) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(coordX);
        buffer.putInt(coordY);
        byte[] byteArray = buffer.array();
        mandarBytesAlDispositivo(byteArray);
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

    /**
     * Recibe el centro del objeto detectado desde el Tracker y delega al sistema Pan-Tilt.
     */
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
    }

    protected void sendMessageFrank(String message) {
    }

    protected void sendHeartbeat(int timeout_ms) {
    }
    protected void setSonarFrequency(int interval_ms) {
    }
    protected void setVoltageFrequency(int interval_ms) {
    }
    protected void setWheelOdometryFrequency(int interval_ms) {
    }

    // --- LÓGICA PRINCIPAL DE SEGUIMIENTO (PAN-TILT) ---

    /**
     * Resetea la cámara a la posición central (0,0) lógica y física.
     * También limpia la memoria integral de los PIDs.
     */
    public void reiniciaPanTilt() {
        currentPan = 0;
        currentTilt = 0;
        panPid.reset();
        tiltPid.reset();
        String jsonCommand = String.format(Locale.US, "{\"T\":133,\"X\":%d,\"Y\":%d,\"SPD\":0,\"ACC\":0}\n", 0, 0);
        mandarBytesAlDispositivo(jsonCommand.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Calcula y aplica el movimiento necesario para centrar el objeto en la pantalla.
     * Utiliza controladores PID para suavizar el movimiento y evitar vibraciones.
     *
     * @param centerPoint Coordenadas X,Y del objeto detectado.
     * @param frameWidth Ancho del canvas/pantalla.
     * @param frameHeight Alto del canvas/pantalla.
     */
    public void trackObject(Point centerPoint, int frameWidth, int frameHeight) {
        if (centerPoint == null) return;

        // El objetivo (Setpoint) es el centro de la pantalla
        int centerX = frameWidth / 2;
        int centerY = frameHeight / 2;

        // --- EJE X (PAN) ---
        // Calculamos ajuste con PID
        float pidOutputX = panPid.calculate(centerX, centerPoint.x);
        int adjustmentX = Math.round(pidOutputX);

        // Limitador de Velocidad (Evita movimientos bruscos > maxPanStep)
        if (adjustmentX > maxPanStep) adjustmentX = maxPanStep;
        else if (adjustmentX < -maxPanStep) adjustmentX = -maxPanStep;

        // Zona Muerta (60px): Si el objeto está cerca del centro, no movemos nada para evitar vibración
        if (Math.abs(centerPoint.x - centerX) > 60) {
            currentPan += adjustmentX;
        }

        // --- EJE Y (TILT) ---
        float pidOutputY = tiltPid.calculate(centerY, centerPoint.y);
        int adjustmentY = Math.round(pidOutputY);

        // Limitador de Velocidad TILT
        if (adjustmentY > maxTiltStep) adjustmentY = maxTiltStep;
        else if (adjustmentY < -maxTiltStep) adjustmentY = -maxTiltStep;

        // Zona Muerta TILT (60px)
        if (Math.abs(centerPoint.y - centerY) > 60) {
            // Signo Negativo (-=): Porque en coordenadas Android Y crece hacia abajo,
            // pero en el robot Tilt positivo es hacia arriba.
            currentTilt -= adjustmentY;
        }

        // Límites de Seguridad (Clamping mecánico)
        currentPan = Math.max(-180, Math.min(180, currentPan));
        currentTilt = Math.max(-30, Math.min(90, currentTilt));

        // Enviar comando final
        mandarPanTilt(currentPan, currentTilt);
    }

    /**
     * Construye el mensaje JSON y lo envía mediante el enrutador general.
     * Incluye control de flujo (Throttling) para no saturar el buffer (máx cada 40ms).
     */
    public void mandarPanTilt(int pan, int tilt) {
        long TiempoActual = System.currentTimeMillis();

        if (TiempoActual - ultimoPanTiltTiempo < PANTILT_INTERVAL_MS) {
            return;
        }

        ultimoPanTiltTiempo = TiempoActual;

        // Formato JSON: {"T":133,"X":pan,"Y":tilt,"SPD":0,"ACC":0}
        String jsonCommand = String.format(Locale.US, "{\"T\":133,\"X\":%d,\"Y\":%d,\"SPD\":0,\"ACC\":0}\n", pan, tilt);
        mandarBytesAlDispositivo(jsonCommand.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------


    private class HeartBeatTask extends TimerTask {
        @Override
        public void run() {
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