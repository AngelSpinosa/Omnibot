package org.openbot.vehicle;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openbot.main.ScanDeviceAdapter;
import org.openbot.utils.Constants;

public class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    // UUID Estándar para puerto serial (SPP)
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter bluetoothAdapter;
    private final Context context;
    public ScanDeviceAdapter adapter;
    public List<BluetoothDevice> deviceList = new ArrayList<>();

    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private int state;

    public static final int STATE_NONE = 0;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;

    private final LocalBroadcastManager localBroadcastManager;

    public BluetoothManager(Context context) {
        this.context = context;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        state = STATE_NONE;
        localBroadcastManager = LocalBroadcastManager.getInstance(context);
        registerReceivers();
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        IntentFilter filterFinished = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        // CORRECCIÓN CRÍTICA PARA ANDROID 14/15 (Crash Loop Fix)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // CAMBIO: Usar RECEIVER_NOT_EXPORTED para mayor seguridad
                context.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                context.registerReceiver(scanReceiver, filterFinished, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(scanReceiver, filter);
                context.registerReceiver(scanReceiver, filterFinished);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registrando receivers: " + e.getMessage());
        }
    }

    // Suprimimos el error "MissingPermission" porque ya lo chequeamos con hasPermission()
    @SuppressLint("MissingPermission")
    public void startScan() {
        if (bluetoothAdapter == null) return;

        deviceList.clear();
        if (adapter != null) adapter.notifyDataSetChanged();

        try {
            // Verificación manual de seguridad
            if (!hasPermission()) {
                Log.e(TAG, "No hay permisos para escanear");
                return;
            }

            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            bluetoothAdapter.startDiscovery();
        } catch (Exception e) {
            Log.e(TAG, "Error al iniciar escaneo: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (bluetoothAdapter != null) {
            try {
                if (!hasPermission()) return;
                bluetoothAdapter.cancelDiscovery();
            } catch (Exception e) {
                Log.e(TAG, "Error al detener escaneo: " + e.getMessage());
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission") // Suprimir error al obtener nombre
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !deviceList.contains(device)) {
                    // Filtramos dispositivos sin nombre para limpiar la lista
                    if(device.getName() != null) {
                        deviceList.add(device);
                        if (adapter != null) adapter.notifyDataSetChanged();
                    }
                }
            }
        }
    };

    public synchronized void connect(BluetoothDevice device) {
        if (state == STATE_CONNECTING) {
            if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        }
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }

        connectThread = new ConnectThread(device);
        connectThread.start();
        setState(STATE_CONNECTING);
    }

    private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();
        setState(STATE_CONNECTED);
    }

    public void write(byte[] out) {
        ConnectedThread r;
        synchronized (this) {
            if (state != STATE_CONNECTED) return;
            r = connectedThread;
        }
        r.write(out);
    }

    public void write(String message) { write(message.getBytes()); }

    public synchronized int getState() { return state; }
    private synchronized void setState(int state) { this.state = state; }
    public boolean isConnected() { return state == STATE_CONNECTED; }

    public void disconnect() {
        stopScan();
        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }
        setState(STATE_NONE);
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        @SuppressLint("MissingPermission") // Suprimimos porque connect() requiere permisos que ya pedimos
        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;
            mmDevice = device;
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) { Log.e(TAG, "Socket create() failed", e); }
            mmSocket = tmp;
        }

        @SuppressLint("MissingPermission")
        public void run() {
            try {
                if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
            } catch (Exception e) {}

            try {
                mmSocket.connect();
            } catch (IOException connectException) {
                try { mmSocket.close(); } catch (IOException closeException) { }
                connectionFailed();
                return;
            }
            synchronized (BluetoothManager.this) { connectThread = null; }
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try { mmSocket.close(); } catch (IOException e) { }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            while (state == STATE_CONNECTED) {
                try {
                    bytes = mmInStream.read(buffer);
                    String readMessage = new String(buffer, 0, bytes);
                    localBroadcastManager.sendBroadcast(
                            new Intent(Constants.DEVICE_ACTION_DATA_RECEIVED)
                                    .putExtra("from", "usb")
                                    .putExtra("data", readMessage));
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }

        public void write(byte[] buffer) {
            try { mmOutStream.write(buffer); } catch (IOException e) { }
        }

        public void cancel() {
            try { mmSocket.close(); } catch (IOException e) { }
        }
    }

    private void connectionFailed() { setState(STATE_NONE); }
    private void connectionLost() { setState(STATE_NONE); }
}