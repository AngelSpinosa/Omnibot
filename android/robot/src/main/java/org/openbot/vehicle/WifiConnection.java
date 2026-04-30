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
 */
public class WifiConnection {
    private DatagramSocket socket;
    private InetAddress address;
    private int port;
    private boolean isConnected = false;
    private final Context context;
    private final LocalBroadcastManager localBroadcastManager;

    // Hilo secundario para no bloquear la interfaz gráfica (Main Thread)
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public WifiConnection(Context context) {
        this.context = context;
        this.localBroadcastManager = LocalBroadcastManager.getInstance(context);
    }

    public void startWifiConnection(String ip, int port) {
        this.port = port;
        // IMPORTANTE: Android prohíbe operaciones de red en el Hilo Principal.
        // Movemos la creación del Socket al hilo secundario.
        executorService.execute(() -> {
            try {
                this.address = InetAddress.getByName(ip);
                this.socket = new DatagramSocket();
                this.isConnected = true;
                Timber.i("Conexión WiFi iniciada exitosamente hacia %s:%d", ip, port);
            } catch (Exception e) {
                Timber.e(e, "Error crítico al iniciar la conexión WiFi UDP");
                this.isConnected = false;
            }
        });
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
            executorService.execute(() -> {
                try {
                    DatagramPacket packet = new DatagramPacket(message, message.length, address, port);
                    socket.send(packet);
                } catch (Exception e) {
                    Timber.e(e, "Error enviando paquete UDP");
                }
            });
        }
    }

    public boolean isOpen() {
        return isConnected;
    }
}