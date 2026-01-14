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


public class  UsbConnection {
  private static final int USB_VENDOR_ID = 6790; // 0x234109YRZ[_KNDSA  aqw67u
  // ]5
  private static final int USB_PRODUCT_ID = 29987; // 0x0001;
  private static final Logger LOGGER = new Logger();

  private final UsbManager usbManager;
  // private UsbDevice usbDevice;
  PendingIntent usbPermissionIntent;
  public static final String ACTION_USB_PERMISSION = "UsbConnection.USB_PERMISSION";

  private UsbDeviceConnection connection;
  private UsbSerialDevice serialDevice;
  private final LocalBroadcastManager localBroadcastManager;
  private String buffer = "";
  private final Context context;
  private final int baudRate;
  private boolean busy;
  private int vendorId;
  private int productId;
  private String productName;
  private String deviceName;
  private String manufacturerName;

  public UsbConnection(Context context, int baudRate) {
    this.context = context;
    this.baudRate = baudRate;
    localBroadcastManager = LocalBroadcastManager.getInstance(this.context);
    usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      usbPermissionIntent =
              PendingIntent.getBroadcast(
                      this.context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    } else {
      usbPermissionIntent =
              PendingIntent.getBroadcast(this.context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    }
  }

  //Recibe datos a traves del USB y los convierte de bytes a una cadena de UTF-8 y los acumula en un bufffer.
  //UsbSerialInterface.UsbReadCallback interfaz llamada cuando se reciben datos de USB
  private final UsbSerialInterface.UsbReadCallback callback =
          //Expresion lambda donde reciben un arreglo de datos bynarios
          data -> {
            try {
              //Linea para convertir los bynarios a String
              String dataUtf8 = new String(data, "UTF-8");
              //Se almacenan en un buffer
              buffer += dataUtf8;
              //Recorrido del buffer
              int index;
              while ((index = buffer.indexOf('\n')) != -1) {
                //Obtiene el buffer y elimina los espacios
                final String dataStr = buffer.substring(0, index).trim();
                //Actualiza el buffer pero ahora sin espacios
                buffer = buffer.length() == index ? "" : buffer.substring(index + 1);

                AsyncTask.execute(() -> onSerialDataReceived(dataStr));
              }
            } catch (UnsupportedEncodingException e) {
              LOGGER.e("Error receiving USB data");
            }
          };

  //Verifica permisos para conexion con el USB
  private final BroadcastReceiver usbReceiver =
          new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
              String action = intent.getAction();
              if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                  UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                  if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (usbDevice != null) {
                      // call method to set up device communication
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


  //Utiliza un broadcast para manejar eventos del dispositivo USB y caracteristicas.
  public boolean startUsbConnection() {
    IntentFilter localIntentFilter = new IntentFilter();
    localIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    localIntentFilter.addAction(ACTION_USB_PERMISSION);

    localBroadcastManager.registerReceiver(usbReceiver, localIntentFilter);
    context.registerReceiver(usbReceiver, localIntentFilter);
    //Obtiene la lista de los dispositivos conectados
    Map<String, UsbDevice> connectedDevices = usbManager.getDeviceList();
    //Verifica que la lista de los dispositos no este vacia
    if (!connectedDevices.isEmpty()) {
      for (UsbDevice usbDevice : connectedDevices.values()) {
        // if (usbDevice.getVendorId() == USB_VENDOR_ID && usbDevice.getProductId() ==
        // USB_PRODUCT_ID) {
        System.out.println("Nombre del dispositivo conectado es: "  + usbDevice.getDeviceName());
        LOGGER.i("Device found: " + usbDevice.getDeviceName());
        if (usbManager.hasPermission(usbDevice)) {
          //Se inicia la conexion serial a traves del metodo startSerialConnection
          return startSerialConnection(usbDevice);
        } else {
          //Pedira que asignes permisos
          usbManager.requestPermission(usbDevice, usbPermissionIntent);
          Toast.makeText(context, "Please allow USB Host connection.", Toast.LENGTH_SHORT).show();
          return false;
        }
        // }
      }
    }
    //Manda un mensaje de que no hay dispositivos USB conectados
    LOGGER.w("Could not start USB connection - No devices found");
    return false;
  }

  //Inicia la conexion con un dispositico USB
  private boolean startSerialConnection(UsbDevice device) {
    LOGGER.i("Ready to open USB device connection");
    Timber.i("Listo para abrir la conexion con el dispositivo USB ");

    connection = usbManager.openDevice(device);
    //System.out.println(connection.toString());

    //Descubre todas las caracteristicas de la USB
    serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection);
    boolean success = false;
    if (serialDevice != null) {
      if (serialDevice.open()) {
        vendorId = device.getVendorId();
        productId = device.getProductId();
        productName = device.getProductName();
        deviceName = device.getDeviceName();
        manufacturerName = device.getManufacturerName();
        serialDevice.setBaudRate(baudRate);
        serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
        serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
        serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
        serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF); // El driver maneja esto

        // --- MODIFICACION IMPORTANTE PARA ESP32/PANTILT ---
        serialDevice.setDTR(true); // Data Terminal Ready
        serialDevice.setRTS(true); // Request To Send
        // --------------------------------------------------

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

  //Maneja los datos recibidos, muestra los logs de datos recibidos  y envia un broadcast
  private void onSerialDataReceived(String data) {
    Timber.d("Datos seriales recibidos desde el USB: " + data);
    localBroadcastManager.sendBroadcast(new Intent(Constants.DEVICE_ACTION_DATA_RECEIVED)
            .putExtra("from", "usb")
            .putExtra("data", data));
  }


  //Cierra la conexion con el dispositivo USB y la limpia
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
    localBroadcastManager.unregisterReceiver(usbReceiver);
    try {
      context.unregisterReceiver(usbReceiver);
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
    }
  }

  public void send(byte[] message) {
    if (isOpen() && !isBusy()) {
      busy = true;

      // Enviar directamente el arreglo de bytes al dispositivo serial
      serialDevice.write(message);

      busy = false;
      // Comentado para evitar spam en logs
      // Timber.i("MENSAJE ENVIADO POR CONEXION SERIAL ES: " + Arrays.toString(message));
    } else {
      Timber.d("USB ocupada, no se pudo enviar el arreglo de bytes");
    }
  }

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