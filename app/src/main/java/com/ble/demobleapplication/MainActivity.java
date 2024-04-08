package com.ble.demobleapplication;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.BLUETOOTH;
import static android.Manifest.permission.BLUETOOTH_ADMIN;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements ItemClickListener {

    private TextView txtDeviceStatus, logTextView, clientDeviceInfoTextView, scanning;
    private ScrollView logScrollView;
    private Button disconnectButton, startScanningButton, clearLog;
    private ProgressBar scanProgress;
    private RecyclerView recyclerview;
    private SwitchCompat idSwitch;

    private List<ScanResult> mList = new ArrayList<ScanResult>();
    private CustomAdapter customAdapter;
    private String[] permission = {
            ACCESS_FINE_LOCATION,
            ACCESS_COARSE_LOCATION,
            BLUETOOTH_ADMIN,
            BLUETOOTH,
            BLUETOOTH_SCAN,
            BLUETOOTH_CONNECT,
    };
    private final int REQUEST_PERMISSION_CODE = 7;
    private final int REQUEST_ENABLE_BT = 1;
    private final int REQUEST_FINE_LOCATION = 2;
    private static final int STORAGE_PERMISSION_CODE = 23;

    private static final long SCAN_PERIOD = 1000;

    private BluetoothAdapter mBluetoothAdapter;

    private BluetoothLeScanner bluetoothLeScanner;

    private boolean isScanning;

    private Handler handler = null;

    private boolean mFilter = false;

    private static UUID SERVICE_UUID = UUID.fromString("ED310001-C889-5D66-AE38-A7A01230635A");

    private ScanCallback leScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    Log.d("TAGG", "onScanResult: " + result);
                    FileLogger.logToFile(getApplicationContext(), "onScanResult: " + result.getDevice().getAddress());
                    boolean isContain = false;
                    for (ScanResult scan : mList) {
                        // code to be executed for each element
                        if (scan.getDevice().getAddress().equals(result.getDevice().getAddress())) {
                            // Found matching person
                            isContain = true;
                            break;
                        }
                    }
                    if (!isContain) {
                        customAdapter.addData(result);
                    }

                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        //txtDeviceStatus = findViewById(R.id.txtDeviceStatus);
        //logTextView = findViewById(R.id.log_text_view);
        //logScrollView = findViewById(R.id.log_scroll_view);
        //disconnectButton = findViewById(R.id.disconnect_button);
        scanProgress = findViewById(R.id.scan_progress);
        startScanningButton = findViewById(R.id.start_scanning_button);
        //clearLog = findViewById(R.id.clear_log_button);
        clientDeviceInfoTextView = findViewById(R.id.client_device_info_text_view);
        scanning = findViewById(R.id.scanning);
        idSwitch = findViewById(R.id.idSwitch);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestForStoragePermissions();
            } else {
                if (!checkingPermissionIsEnabledOrNot()) {
                    requestMultiplePermission();
                } else {

                    String deviceInfo = ("Device Info");
                    clientDeviceInfoTextView.setText(deviceInfo);
                }
            }
        }

        idSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setmFilter(isChecked);
                mList.clear();
                customAdapter.notifyDataSetChanged();
            }
        });
        recyclerview = findViewById(R.id.recyclerview);
        customAdapter = new CustomAdapter(this, mList, this);
        recyclerview.setAdapter(customAdapter);
        startScanningButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (startScanningButton.getText().equals(getString(R.string.start))) {
                    Log.d("TAGG", "onClick: start scanning");
                    //FileLogger.logToFile(getApplicationContext(), "start scanning");
                    //clearLogs();
                    mList.clear();
                    customAdapter.notifyDataSetChanged();
                    SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
                    customAdapter.setClickTime((new Date()).getTime());
                    String msg = dateFormat.format(new Date());
                    scanning.setText("Scanning (" + msg + ")");
                    scanProgress.setVisibility(View.VISIBLE);
                    startScanningButton.setText(getString(R.string.stop));
                    startScan();

                } else {
                    Log.d("TAGG", "onClick: stop");
                    FileLogger.logToFile(getApplicationContext(), "stop scanning");
                    scanProgress.setVisibility(View.INVISIBLE);
                    startScanningButton.setText(getString(R.string.start));
                    stopScanDevice();
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0) {
                boolean write = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                boolean read = grantResults[1] == PackageManager.PERMISSION_GRANTED;

                if (read && write) {
                    Toast.makeText(MainActivity.this, "Storage Permissions Granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Storage Permissions Denied", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private boolean checkingPermissionIsEnabledOrNot() {
        int FirstPermissionResult =
                ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION);
        int SecondPermissionResult =
                ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION);
        int ThirdPermissionResult = ContextCompat.checkSelfPermission(this, BLUETOOTH);
        int ForthPermissionResult =
                ContextCompat.checkSelfPermission(this, BLUETOOTH_ADMIN);
        int fifthPermissionResult = ContextCompat.checkSelfPermission(this, MANAGE_EXTERNAL_STORAGE);
        return FirstPermissionResult == PackageManager.PERMISSION_GRANTED &&
                SecondPermissionResult == PackageManager.PERMISSION_GRANTED &&
                ThirdPermissionResult == PackageManager.PERMISSION_GRANTED &&
                ForthPermissionResult == PackageManager.PERMISSION_GRANTED &&
                fifthPermissionResult == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMultiplePermission() {

        // Creating String Array with Permissions.
        ActivityCompat.requestPermissions(
                this, permission, REQUEST_PERMISSION_CODE
        );
    }

    private void startScan() {
        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        if (mBluetoothAdapter == null) {
            final BluetoothManager bluetoothManager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (handler == null) {
            handler = new Handler();
        }

        if (!hasPermissions()) {
            return;
        }
        bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        scanLeDevice();
    }

    public boolean ismFilter() {
        return mFilter;
    }

    public void setmFilter(boolean mFilter) {
        this.mFilter = mFilter;
    }

    private void scanLeDevice() {
        if (!isScanning) {
            // Stops scanning after a predefined scan period.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.e("TAGG", "scan stop");
                    isScanning = false;
                    //onScanStop(true);
                    stopScanDevice();
                    //bluetoothLeScanner.stopScan(leScanCallback);
                    scanProgress.setVisibility(View.INVISIBLE);
                    startScanningButton.setText(getString(R.string.start));
                    handler = null;
                }
            }, SCAN_PERIOD);

            isScanning = true;
            //onScanStart(true);
            ScanFilter scanFilter;
            if (ismFilter()) {
                Log.e("TAGG", "startScan: filter with " + ismFilter());
                scanFilter = new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                        .build();
            } else {
                Log.e("TAGG", "startScan: filter without " + ismFilter());
                scanFilter = new ScanFilter.Builder()
                        .build();
            }
            Log.e("TAGG", "filter uuid " + scanFilter);
            Log.e("TAGG", "SCAN_PERIOD " + SCAN_PERIOD + "ms");
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(scanFilter);

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            FileLogger.logToFile(getApplicationContext(), "Start Scan");

            bluetoothLeScanner.startScan(filters, settings, leScanCallback);
            //bluetoothLeScanner.startScan(leScanCallback);
        } else {
            stopScanDevice();
        }
    }

    private void stopScanDevice() {

        isScanning = false;
        //onScanStop(true);
        bluetoothLeScanner.stopScan(leScanCallback);
        FileLogger.logToFile(getApplicationContext(), "Stop Scan");
    }

    private boolean hasPermissions() {
        if (!mBluetoothAdapter.isEnabled()) {
            requestBluetoothEnable();
            return false;
        } else if (!hasLocationPermissions()) {
            requestLocationPermission();
            return false;
        }
        return true;
    }

    private void requestBluetoothEnable() {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        //log("Requested user enables Bluetooth. Try starting the scan again.");
    }

    private boolean hasLocationPermissions() {
        return checkSelfPermission(ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[]{ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
        //log("Requested user enable Location. Try starting the scan again.");
    }

    /*@Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_ENABLE_BT: {
                if (resultCode != Activity.RESULT_OK) {
                    requestBluetoothEnable();
                }
            }
        }
    }*/

    @Override
    public void onResume() {
        // Check low energy support
        super.onResume();
        //registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothAdapter != null) {
            //final boolean result = bluetoothService.connect(deviceAddress);
            //Log.d("TAGG", "Connect request result=" + result);
            if (mBluetoothAdapter.isEnabled()) {
                requestBluetoothEnable();
            }
        }


    }

    @Override
    public void onItemClick(BluetoothDevice bluetoothDevice) {
        //final BluetoothDevice device = mLeDeviceListAdapter.getDevice(position);
        Log.d("TAGG", "onItemClick: " + bluetoothDevice);
        //FileLogger.logToFile(getApplicationContext(),  "onItemClick: "+bluetoothDevice);
        if (bluetoothDevice == null)
            return;
        final Intent intent = new Intent(this, DeviceControlActivity.class);
        intent.putExtra(DeviceControlActivity.EXTRAS_DEVICE_NAME, bluetoothDevice.getName());
        intent.putExtra(DeviceControlActivity.EXTRAS_DEVICE_ADDRESS, bluetoothDevice.getAddress());
        if (isScanning) {
            stopScanDevice();
        }
        startActivity(intent);
        Log.d("TAGG", "BLE onItemClick: startActivity true");
    }

    private void requestForStoragePermissions() {
        //Android is 11 (R) or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", this.getPackageName(), null);
                intent.setData(uri);
                storageActivityResultLauncher.launch(intent);
            } catch (Exception e) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                storageActivityResultLauncher.launch(intent);
            }
        } else {
            //Below android 11
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            WRITE_EXTERNAL_STORAGE,
                            READ_EXTERNAL_STORAGE
                    },
                    STORAGE_PERMISSION_CODE
            );
        }

    }

    private ActivityResultLauncher<Intent> storageActivityResultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {

                        @Override
                        public void onActivityResult(ActivityResult o) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                //Android is 11 (R) or above
                                if (Environment.isExternalStorageManager()) {
                                    //Manage External Storage Permissions Granted
                                    Log.d("TAG", "onActivityResult: Manage External Storage Permissions Granted");
                                } else {
                                    Toast.makeText(MainActivity.this, "Storage Permissions Denied", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                //Below android 11

                            }
                            if (!checkingPermissionIsEnabledOrNot()) {
                                requestMultiplePermission();
                            } else {

                                String deviceInfo = ("Device Info");
                                clientDeviceInfoTextView.setText(deviceInfo);
                            }
                        }
                    });
}