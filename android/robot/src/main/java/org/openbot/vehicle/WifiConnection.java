package org.openbot.vehicle;

import android.content.Context;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import timber.log.Timber;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clase WifiConnection
 * --------------------
 * Gestiona la conexión UDP hacia el microcontrolador (ESP32).
 * Usa UDP porque es el estándar para telemetría y control en tiempo real (baja latencia).
 */
public class WifiConnection {
    private DatagramSocket socket;
    private InetAddress address;
    private int port;
    private boolean isConnected = false;
    private final Context context;
    private final LocalBroadcastManager localBroadcastManager;

    // Usamos un ExecutorService para enviar datos en un hilo de fondo
    // sin bloquear la interfaz de usuario ni la cámara.
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public WifiConnection(Context context) {
        this.context = context;
        this.localBroadcastManager = LocalBroadcastManager.getInstance(context);
    }

    public boolean startWifiConnection(String ip, int port) {
        try {
            this.port = port;
            this.address = InetAddress.getByName(ip); // Convierte el String de IP
            this.socket = new DatagramSocket(); // Crea el socket UDP
            this.isConnected = true;
            Timber.i("Conexión WiFi iniciada hacia %s:%d", ip, port);
            return true;
        } catch (Exception e) {
            Timber.e(e, "Error al iniciar la conexión WiFi");
            this.isConnected = false;
            return false;
        }
    }

    public void stopWifiConnection() {
        isConnected = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        Timber.i("Conexión WiFi detenida");
    }

    public void send(byte[] message) {
        if (isConnected && socket != null) {
            // Ejecutamos el envío de red en un hilo secundario
            executorService.execute(() -> {
                try {
                    DatagramPacket packet = new DatagramPacket(message, message.length, address, port);
                    socket.send(packet);
                } catch (Exception e) {
                    Timber.e(e, "Error enviando datos por WiFi");
                }
            });
        } else {
            Timber.d("WiFi no está conectado. No se pudo enviar el mensaje.");
        }
    }

    public boolean isOpen() {
        return isConnected;
    }
}