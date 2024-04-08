package com.ble.demobleapplication;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Created by Chandan Jana on 12-12-2023.
 * Company name: Mindteck
 * Email: chandan.jana@mindteck.com
 */
public class CustomAdapter extends RecyclerView.Adapter<CustomAdapter.ViewHolder> {

    private List<ScanResult> mList;
    private ItemClickListener listener;

    private Context context;

    public CustomAdapter(Context context, List<ScanResult> mList, ItemClickListener listener) {
        this.mList = mList;
        this.listener = listener;
        this.context = context;
    }

    private long time = 0;

    void setClickTime(long time) {
        this.time = time;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.custom_row, parent, false);

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ScanResult scanResult = mList.get(position);

        //holder.device = ItemsViewModel;
        // sets the image to the imageview from our itemHolder class
        holder.imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d("TAGG", "onClick: "+ scanResult.getDevice());
                listener.onItemClick(scanResult.getDevice());
            }
        });

        String name = serverName(scanResult);
        // sets the text to the textview from our itemHolder class
        holder.textView.setText(name);
    }

    String serverName(ScanResult scanResult) {
        String formattedTime = "";
        if (scanResult != null) {

            long timestampNanos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                timestampNanos = scanResult.getTimestampNanos();

            } else {
                timestampNanos = scanResult.getTimestampNanos() + (SystemClock.elapsedRealtimeNanos() - System.currentTimeMillis() * 1_000_000);

            }

            long rxTimestampMillis = System.currentTimeMillis() -
                    SystemClock.elapsedRealtime() +
                    scanResult.getTimestampNanos() / 1000000;
            long rxDate = rxTimestampMillis - time;
            formattedTime = String.valueOf(rxDate);
        }

        BluetoothDevice mBluetoothDevice = scanResult.getDevice();
        String name = "";
        if (mBluetoothDevice.getName() != null) {
            return name.concat("Name: " + mBluetoothDevice.getName().concat("\nAddress: " + mBluetoothDevice.getAddress()).concat("\nTime: " + formattedTime));
        } else {
            return name.concat("Name: Unamed".concat("\nAddress: " + mBluetoothDevice.getAddress()).concat("\nTime: " + formattedTime));

        }
    }

    @Override
    public int getItemCount() {
        return mList.size();
    }

    void addData(ScanResult list) {
        mList.add(list);
        notifyItemInserted(mList.size() - 1);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        Button imageView;
        TextView textView;

        ViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.connect_gatt_server_button);
            textView = itemView.findViewById(R.id.gatt_server_name_text_view);
        }
    }
}
