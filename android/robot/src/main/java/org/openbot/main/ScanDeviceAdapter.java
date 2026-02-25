package org.openbot.main;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.util.SparseArray;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import java.util.List;
import org.openbot.R;

public class ScanDeviceAdapter extends CommonRecyclerViewAdapter<BluetoothDevice> {

  public ScanDeviceAdapter(
          @NonNull Context context,
          @NonNull List<BluetoothDevice> dataList,
          @NonNull SparseArray<int[]> resLayoutAndViewIds) {
    super(context, dataList, resLayoutAndViewIds);
  }

  @Override
  public int getItemResLayoutType(int position) {
    return 0;
  }

  @Override
  public void bindDataToItem(MyViewHolder holder, BluetoothDevice data, int position) {
    // 1. Obtenemos las referencias a los TextViews usando findViewById estándar
    TextView nameView = holder.itemView.findViewById(R.id.ble_name);
    TextView addressView = holder.itemView.findViewById(R.id.ble_address);
    TextView statusView = holder.itemView.findViewById(R.id.ble_connection_state);

    // 2. Verificación de permisos y asignación de nombre
    if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
      if (nameView != null) nameView.setText("Desconocido");
    } else {
      String name = data.getName();
      if (name == null || name.isEmpty()) name = "Sin Nombre";
      if (nameView != null) nameView.setText(name);
    }

    // 3. Asignación de dirección MAC
    if (addressView != null) addressView.setText(data.getAddress());

    // 4. Estado de conexión (Visual)
    if (statusView != null) {
      statusView.setText("Disponible");
      statusView.setTextColor(Color.GRAY);
    }
  }
}