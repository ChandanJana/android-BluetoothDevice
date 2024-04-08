package com.ble.demobleapplication;

import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

public class AndroidNativeBluetooth {
    // Constants
    private static String CLIENT_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    // Bluetooth scan specific
    private static String service_uuid_str = "";
    private static String mac_address_str = "";
    private static long scan_timeout = -1;

    // State management specific
    private static boolean mScanning = false;

    private static BluetoothGattService uart = null;
    private static BluetoothGattCharacteristic rx = null;
    private static BluetoothGattCharacteristic tx = null;
    private static BluetoothGatt gatt = null;
    private static BluetoothManager bluetoothManager = null;
    private static BluetoothAdapter bluetoothAdapter = null;
    private static ScanCallback mScanCallback = null;
    private static BluetoothLeScanner mBluetoothLeScanner = null;
    private static Handler mHandler = null;
    private static Context mContext = null;
    private static BluetoothDevice mmDevice = null;

    private static byte[] ioBuffer = null;

    //Holds discovered characteristics
    private static List<BluetoothGattCharacteristic> discoveredCharacteristics = null;

    //Holds the discovered devices
    private static List<String> deviceList = new ArrayList();

    private static Boolean isBonded = false;

    private static Queue<Runnable> commandQueue = new LinkedList<>();
    private static boolean commandQueueBusy;
    private static int retries = 0;
    private static Handler bleHandler = new Handler(Looper.getMainLooper());
    static BluetoothGatt bluetoothGatt = null;
    static boolean isRetrying;

    private static final int MAX_TRIES = 5;

    private static final String byteString = "7EA0210223F193730A81801405020500060205000704000000010804000000017F657E";

    // -------------------------------------------------------- //
    // Callbacks for errors
    // To be called if there is any error occurrence
    private static native void errorEvent(String errorMessage, String errorCode);
    // -------------------------------------------------------- //

    // -------------------------------------------------------- //
    // Callbacks for scanning operations
    // To be called for every unique device discovered
    private static native void deviceDiscovered(String name, String macAddress, String rssi, String rawData);

    // To be called when scanning finishes - either due to timeout or stopScanning was called from C++
    private static native void scanComplete(String serviceScanned);
    // -------------------------------------------------------- //

    // -------------------------------------------------------- //
    // Callbacks for connect and Tx/Rx operations
    // To be called whenever a device connects
    private static native void deviceConnected(String macAddress);

    // To be called whenever a device disconnects
    private static native void deviceDisonnected(String macAddress);

    // To be called whenever a data is received from the connected device
    private static native void dataReceived(byte[] strdataHexFormat);
    // -------------------------------------------------------- //

    // Internal functions

    private static void nextCommand() {
        // If there is still a command being executed then bail out
        if (commandQueueBusy) {
            return;
        }

        // Check if we still have a valid gatt object
        if (bluetoothGatt == null) {
            Log.e(TAG, String.format("ERROR: GATT is 'null' for peripheral '%s', clearing command queue"));
            commandQueue.clear();
            commandQueueBusy = false;
            return;
        }

        // Execute the next command in the queue
        if (commandQueue.size() > 0) {
            final Runnable bluetoothCommand = commandQueue.peek();
            commandQueueBusy = true;
            retries = 0;
            bleHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        bluetoothCommand.run();
                    } catch (Exception ex) {
                        Log.e(TAG, String.format("ERROR: Command exception for device '%s'"), ex);
                    }
                }
            });
        }
    }

    private static void completedCommand() {
        commandQueueBusy = false;
        isRetrying = false;
        commandQueue.poll();
        nextCommand();
    }

    private static void retryCommand() {
        commandQueueBusy = false;
        Runnable currentCommand = commandQueue.peek();
        if (currentCommand != null) {
            if (retries >= MAX_TRIES) {
                // Max retries reached, give up on this one and proceed
                Log.v(TAG, "Max number of tries reached");
                commandQueue.poll();
            } else {
                isRetrying = true;
            }
        }
        nextCommand();
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];

        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }

        return new String(hexChars).toUpperCase();
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static boolean isCharacteristicWritable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    private static boolean isCharacteristicReadable(BluetoothGattCharacteristic pChar) {

        return (pChar.getProperties() & (BluetoothGattCharacteristic.PROPERTY_READ)) != 0;
    }

    public static boolean isCharacteristicNotifiable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
    }

    public static boolean isCharacteristicIndicatable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }
    // Internal functions

    public AndroidNativeBluetooth() {
    }

    public AndroidNativeBluetooth(Context mContext) {
        if (mContext == null) {
            System.out.println("WARNING: Context must not be null");
            return;
        }

        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        }

        if (bluetoothAdapter == null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        System.out.println("SUCCESS: Created AndroidNativeBle object");
    }

    // To be called from C/C++ code to set the scanning timeout
    public static void setTimeOut(String timeoutvalue) {
        scan_timeout = Long.parseLong(timeoutvalue);
        System.out.println("INFO: Set timeout value: " + timeoutvalue);
    }

    // To be called from C/C++ code to set the service UUID
    public static void setUUID(String uuidstr) {
        service_uuid_str = uuidstr;
        System.out.println("INFO: Set UUID value: " + uuidstr);
    }

    // To be called from C/C++ code to set the application context (cotext of Main Activty)
    public static void setContext(Context ctx) {
        if (ctx == null) {
            System.out.println("ERROR: Context must not be null");
            return;
        }

        mContext = ctx;
        System.out.println("INFO: Context set by C++ caller");
    }

    // To be called by C/C++ to start scanning
    public static void startScanning() {
        if (mContext == null) {
            System.out.println("ERROR: Context must not be null");
            return;
        }

        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        }

        if (bluetoothAdapter == null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (service_uuid_str == null || service_uuid_str.isEmpty()) {
            System.out.println("ERROR: UUID must not be blank/empty or null");
            return;
        }

        if (scan_timeout == -1) {
            System.out.println("ERROR: Scan time out must not be -1");
            return;
        }

        System.out.println("INFO: Start scanning with service UUID " + service_uuid_str + " and millisecond timeout " + scan_timeout);

        deviceList.clear();

        if (!mScanning) {
            mScanCallback = new BluetoothScanCallback();
            mBluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

            // Note: Filtering does not work the same (or at all) on most devices. It also is unable to
            // search for a mask or anything less than a full UUID.
            // Unless the full UUID of the server is known, manual filtering may be necessary.
            // For example, when looking for a brand of device that contains a char sequence in the UUID

            ScanFilter scanFilter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(UUID.fromString(service_uuid_str)))
                    .build();

            List<ScanFilter> filters = new ArrayList<>();
            filters.add(scanFilter);

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build();

            mBluetoothLeScanner.startScan(filters, settings, mScanCallback);

            mScanning = true;
        }
    }

    // To be called from C/C++ code to stop scanning
    public static void stopScanning() {
        System.out.println("Stop scanning");

        if (mScanning && bluetoothAdapter != null && bluetoothAdapter.isEnabled() && mBluetoothLeScanner != null) {
            mBluetoothLeScanner.stopScan(mScanCallback);
            //scanComplete(service_uuid_str);
        }

        AndroidNativeBluetooth.bluetoothManager = null;
        AndroidNativeBluetooth.bluetoothAdapter = null;
        mScanCallback = null;
        mScanning = false;
        mHandler = null;
    }

    //Scan callback
    private static class BluetoothScanCallback extends ScanCallback {
        BluetoothScanCallback() {
        }

        private void processScanResult(ScanResult result) {
            String macAddress = result.getDevice().getAddress();
            // Do not fire for devices that are already discovered
            if (deviceList.contains(macAddress)) {
                return;
            }

            System.out.println("INFO: processScanResult");

            deviceList.add(macAddress);

            int rssi = result.getRssi();
            String deviceName = result.getDevice().getName();
            String rawData = bytesToHex(result.getScanRecord().getBytes());

            if (deviceName == null) {
                deviceName = "Unknown Device";
            }

            //deviceDiscovered(deviceName, macAddress, String.valueOf(rssi), rawData);

            System.out.println("INFO: onScanResult: device Name " + deviceName);
            System.out.println("INFO: onScanResult: rssi " + rssi);
            System.out.println("INFO: onScanResult: mac address " + macAddress);
            System.out.println("INFO: onScanResult: raw data length " + rawData.length());
            System.out.println("INFO: onScanResult: raw data " + rawData);
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                processScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            //errorEvent("Scan failed", String.valueOf(errorCode));
        }
    }

    public static final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            System.out.println("INFO: BroadcastReceiver::onReceive()");
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (mmDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
                    System.out.println("SUCCESS: Device Bonded");
                    isBonded = true;
                    mmDevice.connectGatt(mContext, false, mGattCallback);
                }
            }
        }
    };

    // To be called from C/C++ code to connect to a peripheral
    // MAC address is also provided to ensure that correct device is invoked

    public static void connect(String macAddress) {
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            System.out.println("WARNING: Bluetooth Manager was null");
        }

        if (bluetoothAdapter == null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            System.out.println("INFO: Bluetooth Adapter was null");
        }

        gatt = null;
        rx = null;
        tx = null;
        uart = null;

        mac_address_str = macAddress;
        System.out.println("INFO: Set MAC Address value for connection: " + mac_address_str);
        mmDevice = bluetoothAdapter.getRemoteDevice(mac_address_str);

        if (mmDevice != null) {
            System.out.println("INFO: Device is valid");
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            mContext.registerReceiver(mReceiver, filter);
            gatt = mmDevice.connectGatt(mContext, false, mGattCallback);
        }
    }

    // To be called from C/C++ code to disconnect from a peripheral
    // MAC address is also provided to ensure that correct device is invoked
    public static void disconnect(String macAddress) {
        gatt.disconnect();
    }

    // To be called from C/C++ code to send data to the connected remote device
    // MAC address is also provided to ensure that correct device is invoked
    // Java code must also convert the string format data len to integer and compare that to data len of the byte array converted from hex string
    public static void send(final byte[] dataHexFormat) {
        ioBuffer = null;
        if (gatt == null || uart == null || rx == null || tx == null) {
            return;
        }

        System.out.println("INFO: send() via " + tx.getUuid().toString().toUpperCase());
        System.out.println(bytesToHex(dataHexFormat));
        System.out.println("INFO: send() via " + new String(dataHexFormat, StandardCharsets.UTF_8));

        if (!isCharacteristicWritable(tx)) {
            System.out.println("ERROR: NO WRITE POSSIBLE");
            return;
        }

        if (!isCharacteristicReadable(tx)) {
            System.out.println("ERROR: NO READ POSSIBLE");
        }

        if ((tx.getProperties() & PROPERTY_WRITE_NO_RESPONSE) != 0) {
            tx.setWriteType(WRITE_TYPE_NO_RESPONSE);
        } else {
            tx.setWriteType(WRITE_TYPE_DEFAULT);
        }

        tx.setValue(dataHexFormat);

        bluetoothGatt = gatt;
        boolean result = commandQueue.add(new Runnable() {
            @Override
            public void run() {

                if (!bluetoothGatt.writeCharacteristic(tx)) {
                    System.out.println("ERROR: GATT write failed");
                    completedCommand();
                } else {
                    System.out.println("INFO: write Requested");
                    retries++;
                }
            }
        });

        if (result) {
            nextCommand();
        } else {
            Log.e(TAG, "ERROR: Could not enqueue read characteristic command");
        }

        //gatt.writeCharacteristic(tx, dataHexFormat, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
    }


    private static BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (status == GATT_SUCCESS) {

                if (isBonded) {

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        System.out.println("SUCCESS: Connected");
                        if (status == BluetoothGatt.GATT_SUCCESS) {

                            bluetoothGatt = gatt;
                            boolean result = commandQueue.add(new Runnable() {
                                @Override
                                public void run() {
                                    if (!bluetoothGatt.discoverServices()) {
                                        System.out.println("ERROR: GATT Service discovery failed");
                                        completedCommand();
                                    } else {
                                        System.out.println("INFO: Service Discovery Requested");
                                        retries++;
                                    }
                                }
                            });

                            if (result) {
                                nextCommand();
                            } else {
                                Log.e(TAG, "ERROR: Could not enqueue read characteristic command");
                            }
                        } else {
                            if (status == BluetoothGatt.GATT_READ_NOT_PERMITTED) {
                                System.out.println("ERROR: GATT read operation is not permitted");
                            } else {
                                if (status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED) {
                                    System.out.println("ERROR: GATT write operation is not permitted");
                                } else {
                                    if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                                        System.out.println("ERROR: Insufficient authentication for a given operation");
                                    } else {
                                        if (status == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED) {
                                            System.out.println("ERROR: The given request is not supported");
                                        } else {
                                            System.out.println("ERROR: A GATT operation failed");
                                        }
                                    }
                                }
                            }
                        }

                        //stopScanning();
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        System.out.println("INFO: BLE Disconnected");
                        //deviceDisonnected(mac_address_str);
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTING) {
                        System.out.println("INFO: BLE Connecting");
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_DISCONNECTING) {
                        System.out.println("INFO: BLE Disconnecting");
                        return;
                    }

                    System.out.println("WARNING: Unhandled connection state");

                }

            } else {

                if (isBonded)
                    retryCommand();

            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {

                final List<BluetoothGattService> services = gatt.getServices();

                boolean found = false;

                for (BluetoothGattService service : services) {
                    if (service_uuid_str.equals(service.getUuid().toString().toUpperCase())) {
                        System.out.println("INFO: Service Discovery Finished");
                        found = true;
                        discoveredCharacteristics = service.getCharacteristics();
                        uart = service;
                        break;
                    }
                }

                if (found) {
                    final List<BluetoothGattCharacteristic> characteristics = uart.getCharacteristics();

                    // Get the read/notify characteristic

                    for (BluetoothGattCharacteristic characteristic : characteristics) {
                        int prop = characteristic.getProperties();

                        if (isCharacteristicNotifiable(characteristic)) {
                            rx = characteristic;
                            System.out.println("SUCCESS: RX Found");
                        }

                        if (isCharacteristicWritable(characteristic)) {
                            tx = characteristic;
                            System.out.println("SUCCESS: TX Found");
                        }
                    }

                    if (rx != null) {
                        // Setup notifications on RX characteristic changes (i.e. data received).
                        // First call setCharacteristicNotification to enable notification.
                        if (!gatt.setCharacteristicNotification(rx, true)) {
                            System.out.println("Couldn't set notifications for RX characteristic!");
                            //errorEvent("ERROR: Couldn't set notifications for RX characteristic!","103");
                        } else {
                            // Next update the RX characteristic's client descriptor to enable notifications.
                            if (rx.getDescriptor(UUID.fromString(CLIENT_UUID)) != null) {
                                BluetoothGattDescriptor desc = rx.getDescriptor(UUID.fromString(CLIENT_UUID));
                                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                bluetoothGatt = gatt;
                                boolean result = commandQueue.add(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!bluetoothGatt.writeDescriptor(desc)) {
                                            System.out.println("ERROR: Couldn't write RX client descriptor value!");
                                            completedCommand();
                                        } else {
                                            System.out.println("SUCCESS: RX Notifications subscribed");
                                            retries++;
                                        }
                                    }
                                });

                                if (result) {
                                    nextCommand();
                                } else {
                                    Log.e(TAG, "ERROR: Could not enqueue read characteristic command");
                                }

                            } else {
                                System.out.println("ERROR: Couldn't get RX client descriptor!");
                            }
                        }
                    }

                    if (tx != null) {
                        if (isCharacteristicIndicatable(tx)) {
                            System.out.println("INFO: Setting indication on TX possible");
                            // Setup notifications on TX characteristic changes (i.e. data sent).
                            // First call setCharacteristicIndication to enable indication.
                            if (!gatt.setCharacteristicNotification(tx, true)) {
                                System.out.println("Couldn't set indications for TX characteristic!");
                                //errorEvent("ERROR: Couldn't set indications for TX characteristic!","103");
                            } else {
                                // Next update the TX characteristic's client descriptor to enable indications.
                                if (tx.getDescriptor(UUID.fromString(CLIENT_UUID)) != null) {
                                    BluetoothGattDescriptor desc = tx.getDescriptor(UUID.fromString(CLIENT_UUID));
                                    desc.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);

                                    bluetoothGatt = gatt;
                                    boolean result = commandQueue.add(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!bluetoothGatt.writeDescriptor(desc)) {
                                                System.out.println("ERROR: Couldn't write TX client descriptor value!");
                                                completedCommand();
                                            } else {
                                                System.out.println("SUCCESS: TX Notifications subscribed");
                                                retries++;
                                            }
                                        }
                                    });

                                    if (result) {
                                        nextCommand();
                                    } else {
                                        Log.e(TAG, "ERROR: Could not enqueue read characteristic command");
                                    }

                                } else {
                                    System.out.println("ERROR: Couldn't get TX client descriptor!");
                                }
                            }
                        } else {
                            System.out.println("WARNING: Cannot set indication on TX");
                        }
                    }
                }

                completedCommand();
            } else {
                if (isBonded)
                    retryCommand();
                System.out.println("INFO: onServicesDiscoveonConnectionStateChanged() received: " + status);
            }
        }


        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            System.out.println("SUCCESS: Negotiated MTU " + mtu);
            if (status == GATT_SUCCESS) {
                send(hexStringToByteArray(byteString));
                completedCommand();
                //deviceConnected(mac_address_str);
            } else {

                if (isBonded)
                    retryCommand();

            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            System.out.println("INFO: onDescriptorRead");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            System.out.println("INFO: onDescriptorWrite " + status);
            if (status == GATT_SUCCESS) {
                bluetoothGatt = gatt;
                boolean result = commandQueue.add(new Runnable() {
                    @Override
                    public void run() {
                        if (!bluetoothGatt.requestMtu(517)) {
                            System.out.println("ERROR: Couldn't equest MTUR!");
                            completedCommand();
                        } else {
                            System.out.println("SUCCESS: MTU Requested Successfully!");
                            retries++;
                        }
                    }
                });

                if (result) {
                    nextCommand();
                } else {
                    Log.e(TAG, "ERROR: Could not enqueue read characteristic command");
                }

                completedCommand();

            } else {

                if (isBonded)
                    retryCommand();

            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            System.out.println("INFO: onCharacteristicRead");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            System.out.println("INFO: onCharacteristicWrite");
            if (status == GATT_SUCCESS) {
                System.out.println("INFO: onCharacteristicWrite Success");
                completedCommand();

            } else {
                System.out.println("INFO: onCharacteristicWrite failed");
                retryCommand();

            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            System.out.println("INFO: onCharacteristicChanged");

            ioBuffer = characteristic.getValue();
            String byteArrayToStr = new String(ioBuffer, StandardCharsets.US_ASCII);
            System.out.println("Received data of length " + byteArrayToStr.length());
            //dataReceived(ioBuffer);
            ioBuffer = null;

            completedCommand();
        }
    };
}
