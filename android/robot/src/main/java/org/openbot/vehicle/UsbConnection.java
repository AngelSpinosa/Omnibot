// Created by Matthias Mueller - Intel Intelligent Systems Lab - 2020

package org.openbot.vehicle;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Map;
import org.openbot.env.Logger;
import org.openbot.utils.Constants;
import timber.log.Timber;
import java.nio.charset.StandardCharsets;

/**
 * Clase UsbConnection
 * -------------------
 * Esta clase gestiona la comunicación serial a través de USB entre el dispositivo Android
 * y el microcontrolador del robot (Arduino/ESP32).
 * * Funciones principales:
 * - Detectar conexión/desconexión de USB.
 * - Solicitar permisos de usuario.
 * - Configurar la conexión serial (BaudRate, DTR, RTS).
 * - Leer datos entrantes y difundirlos (Broadcast) a la app.
 * - Enviar comandos (bytes) al robot.
 */
public class  UsbConnection {
  private static final int USB_VENDOR_ID = 6790; // ID del fabricante (no se usa estrictamente en la lógica actual de filtrado)
  private static final int USB_PRODUCT_ID = 29987; // ID del producto
  private static final Logger LOGGER = new Logger();

  private final UsbManager usbManager;
  // private UsbDevice usbDevice;
  PendingIntent usbPermissionIntent;
  public static final String ACTION_USB_PERMISSION = "UsbConnection.USB_PERMISSION";

  private UsbDeviceConnection connection;
  private UsbSerialDevice serialDevice;
  private final LocalBroadcastManager localBroadcastManager;
  private String buffer = ""; // Buffer para acumular fragmentos de datos recibidos
  private final Context context;
  private final int baudRate; // Velocidad de comunicación (ej. 115200)
  private boolean busy; // Bandera para evitar conflictos de escritura simultánea
  private int vendorId;
  private int productId;
  private String productName;
  private String deviceName;
  private String manufacturerName;

  /**
   * Constructor de la clase.
   * Inicializa el administrador de USB y prepara los Intents para permisos.
   *
   * @param context Contexto de la aplicación (Activity o Application).
   * @param baudRate Velocidad de transmisión serial deseada.
   */
  public UsbConnection(Context context, int baudRate) {
    this.context = context;
    this.baudRate = baudRate;
    localBroadcastManager = LocalBroadcastManager.getInstance(this.context);
    usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

    // Configuración de Intents para permisos, compatible con versiones nuevas de Android (Flag Immutable)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      usbPermissionIntent =
              PendingIntent.getBroadcast(
                      this.context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    } else {
      usbPermissionIntent =
              PendingIntent.getBroadcast(this.context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    }
  }

  /**
   * Callback de Lectura (UsbReadCallback)
   * -------------------------------------
   * Se ejecuta automáticamente cada vez que el puerto serial recibe datos crudos (bytes).
   * 1. Convierte bytes a String (UTF-8).
   * 2. Acumula en un 'buffer'.
   * 3. Busca el carácter de nueva línea '\n' que indica el fin de un mensaje.
   * 4. Extrae el mensaje completo y lo procesa en 'onSerialDataReceived'.
   */
  private final UsbSerialInterface.UsbReadCallback callback =
          data -> {
            try {
              // Convertir bytes a texto
              String dataUtf8 = new String(data, "UTF-8");
              // Acumular en buffer
              buffer += dataUtf8;

              // Procesar mensajes completos terminados en '\n'
              int index;
              while ((index = buffer.indexOf('\n')) != -1) {
                final String dataStr = buffer.substring(0, index).trim();
                buffer = buffer.length() == index ? "" : buffer.substring(index + 1);

                // Ejecutar el procesamiento en segundo plano
                AsyncTask.execute(() -> onSerialDataReceived(dataStr));
              }
            } catch (UnsupportedEncodingException e) {
              LOGGER.e("Error receiving USB data");
            }
          };

  /**
   * BroadcastReceiver para Eventos USB
   * --------------------------------
   * Escucha eventos del sistema operativo:
   * 1. ACTION_USB_PERMISSION: El usuario aceptó/denegó el permiso.
   * 2. ACTION_USB_DEVICE_DETACHED: El cable se desconectó.
   */
  private final BroadcastReceiver usbReceiver =
          new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
              String action = intent.getAction();
              if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                  UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                  if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (usbDevice != null) {
                      // Permiso concedido, iniciar conexión
                      startSerialConnection(usbDevice);
                    }
                  } else {
                    LOGGER.d("Permission denied for device " + usbDevice);
                    Toast.makeText(
                                    UsbConnection.this.context,
                                    "USB Host permission is required!",
                                    Toast.LENGTH_LONG)
                            .show();
                  }
                }
              } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                LOGGER.i("USB device detached");
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                  stopUsbConnection();
                }
              }
            }
          };


  /**
   * Inicia el proceso de conexión USB.
   * Registra los receptores de eventos y busca dispositivos conectados.
   *
   * @return true si se inició la conexión exitosamente, false si no hay dispositivos o falta permiso.
   */
  public boolean startUsbConnection() {
    IntentFilter localIntentFilter = new IntentFilter();
    localIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    localIntentFilter.addAction(ACTION_USB_PERMISSION);

    localBroadcastManager.registerReceiver(usbReceiver, localIntentFilter);
    context.registerReceiver(usbReceiver, localIntentFilter);

    // Obtiene lista de dispositivos conectados al OTG
    Map<String, UsbDevice> connectedDevices = usbManager.getDeviceList();

    if (!connectedDevices.isEmpty()) {
      for (UsbDevice usbDevice : connectedDevices.values()) {
        System.out.println("Nombre del dispositivo conectado es: "  + usbDevice.getDeviceName());
        LOGGER.i("Device found: " + usbDevice.getDeviceName());

        // Verificar si ya tenemos permiso
        if (usbManager.hasPermission(usbDevice)) {
          return startSerialConnection(usbDevice);
        } else {
          // Solicitar permiso al usuario
          usbManager.requestPermission(usbDevice, usbPermissionIntent);
          Toast.makeText(context, "Please allow USB Host connection.", Toast.LENGTH_SHORT).show();
          return false;
        }
      }
    }

    LOGGER.w("Could not start USB connection - No devices found");
    return false;
  }

  /**
   * Abre la conexión serial con un dispositivo específico.
   * Configura los parámetros críticos (BaudRate, DTR, RTS).
   */
  private boolean startSerialConnection(UsbDevice device) {
    LOGGER.i("Ready to open USB device connection");
    Timber.i("Listo para abrir la conexion con el dispositivo USB ");

    connection = usbManager.openDevice(device);
    serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection);

    boolean success = false;
    if (serialDevice != null) {
      if (serialDevice.open()) {
        // Guardar metadatos del dispositivo
        vendorId = device.getVendorId();
        productId = device.getProductId();
        productName = device.getProductName();
        deviceName = device.getDeviceName();
        manufacturerName = device.getManufacturerName();

        // Configuración Serial
        serialDevice.setBaudRate(baudRate);
        serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
        serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
        serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
        serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

        // --- CONFIGURACIÓN CRÍTICA PARA ESP32/PANTILT ---
        // Activar DTR y RTS es necesario para ciertos drivers (como CP2102)
        // para que inicien la transmisión de datos.
        serialDevice.setDTR(true); // Data Terminal Ready
        serialDevice.setRTS(true); // Request To Send
        // --------------------------------------------------

        // Iniciar la escucha de datos
        serialDevice.read(callback);
        LOGGER.i("Serial connection opened");
        success = true;
      } else {
        LOGGER.w("Cannot open serial connection");
      }
    } else {
      LOGGER.w("Could not create Usb Serial Device");
    }
    return success;
  }

  /**
   * Procesa los datos recibidos (texto limpio) y los envía al resto de la App.
   * Usa LocalBroadcastManager para desacoplar la conexión de la lógica del vehículo.
   */
  private void onSerialDataReceived(String data) {
    Timber.d("Datos seriales recibidos desde el USB: " + data);

    // Enviar mensaje a quien esté escuchando (ej. Vehicle.java o MainActivity)
    localBroadcastManager.sendBroadcast(new Intent(Constants.DEVICE_ACTION_DATA_RECEIVED)
            .putExtra("from", "usb")
            .putExtra("data", data));
  }


  /**
   * Cierra la conexión USB y libera recursos.
   * Debe llamarse al cerrar la app o pausar el fragmento.
   */
  public void stopUsbConnection() {
    try {
      if (serialDevice != null) {
        serialDevice.close();
      }
      if (connection != null) {
        connection.close();
      }
    } finally {
      serialDevice = null;
      connection = null;
    }
    // Desregistrar receptores para evitar fugas de memoria
    localBroadcastManager.unregisterReceiver(usbReceiver);
    try {
      context.unregisterReceiver(usbReceiver);
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
    }
  }

  /**
   * Envía un arreglo de bytes al dispositivo serial.
   * Esta es la función que usa Vehicle.java para mandar los comandos JSON.
   * * @param message Arreglo de bytes a enviar (ej. "hola\n".getBytes())
   */
  public void send(byte[] message) {
    if (isOpen() && !isBusy()) {
      busy = true;

      // Escritura asíncrona al puerto serial
      serialDevice.write(message);

      busy = false;
      // Timber.i("MENSAJE ENVIADO: " + Arrays.toString(message)); // Debug opcional
    } else {
      Timber.d("USB ocupada o desconectada, no se pudo enviar.");
    }
  }

  // --- Métodos Getters y de Estado ---

  public boolean isOpen() {
    return connection != null;
  }

  public boolean isBusy() {
    return busy;
  }

  public int getBaudRate() {
    return baudRate;
  }

  public int getVendorId() {
    return vendorId;
  }

  public int getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public String getDeviceName() {
    return deviceName;
  }

  public String getManufacturerName() {
    return manufacturerName;
  }
}