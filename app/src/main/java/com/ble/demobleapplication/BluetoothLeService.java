/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ble.demobleapplication;

import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
import static com.ble.demobleapplication.SampleGattAttributes.isCharacteristicIndicable;
import static com.ble.demobleapplication.SampleGattAttributes.isCharacteristicNotifiable;
import static com.ble.demobleapplication.SampleGattAttributes.isCharacteristicReadable;
import static com.ble.demobleapplication.SampleGattAttributes.isCharacteristicWritable;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED = "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE = "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA = "com.example.bluetooth.le.EXTRA_DATA";
    private static UUID SERVICE_UUID = UUID.fromString("ED310001-C889-5D66-AE38-A7A01230635A");
    private static String CLIENT_UUID = "00002902-0000-1000-8000-00805f9b34fb";
    private static final String byteString = "7EA0210223F193730A81801405020500060205000704000000010804000000017F657E";

    public final static UUID UUID_HEART_RATE_MEASUREMENT = UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);
    private boolean isBonded = false;
    private static Queue<Runnable> commandQueue = new LinkedList<>();

    private static boolean commandQueueBusy;
    private static int retries = 0;

    static boolean isRetrying;

    private static final int MAX_TRIES = 5;

    private static Handler bleHandler = new Handler(Looper.getMainLooper());
    private static BluetoothGattService uart = null;
    private static BluetoothGattCharacteristic rx = null;
    private static BluetoothGattCharacteristic tx = null;

    private static byte[] ioBuffer = null;

    public final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "INFO: BLE BroadcastReceiver::onReceive()");
            String action = intent.getAction();
            final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mBluetoothDeviceAddress);
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "BLE SUCCESS: Device Bonded");
                    Log.d("TAGG", "BLE SUCCESS: Device Bonded");
                    FileLogger.logToFile(getApplicationContext(), "BLE SUCCESS: Device Bonded");
                    isBonded = true;
                    device.connectGatt(BluetoothLeService.this, false, mGattCallback);
                }else if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                    Log.d("TAGG", "BLE SUCCESS: Device Bond NONE");
                    FileLogger.logToFile(getApplicationContext(), "BLE SUCCESS: Device Bond NONE");
                    if (mBluetoothGatt != null) {
                        mBluetoothGatt.close();
                    }
                    isBonded = false;
                    mBluetoothGatt = null;
                    Log.d(TAG, "BLE Trying to create a new connection.");
                    Log.d("TAGG", "BLE Trying to create a new connection.");
                    FileLogger.logToFile(getApplicationContext(), "BLE Trying to create a new connection.");
                    mBluetoothGatt = device.connectGatt(getApplicationContext(), false, mGattCallback);
                    mConnectionState = STATE_CONNECTING;
                }
            }
        }
    };

    private void completedCommand() {
        Log.d("TAGG", "completedCommand called");
        //FileLogger.logToFile(getApplicationContext(), "completedCommand called");
        commandQueueBusy = false;
        isRetrying = false;
        commandQueue.poll();
        nextCommand();
    }

    private void nextCommand() {
        Log.d("TAGG", "nextCommand called");
        //FileLogger.logToFile(getApplicationContext(), "nextCommand called");
        // If there is still a command being executed then bail out
        if (commandQueueBusy) {
            Log.d("TAGG", "commandQueueBusy");
            //FileLogger.logToFile(getApplicationContext(), "commandQueue is Busy");
            return;
        }

        // Check if we still have a valid gatt object
        if (mBluetoothGatt == null) {
            Log.d("TAGG", String.format("BLE ERROR: GATT is 'null' for peripheral '%s', clearing command queue"));
            Log.e(TAG, String.format("BLE ERROR: GATT is 'null' for peripheral '%s', clearing command queue"));
            //FileLogger.logToFile(getApplicationContext(), String.format("BLE ERROR: GATT is 'null' for peripheral '%s', clearing command queue"));
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
                        Log.d("TAGG", "bluetoothCommand run");
                        //FileLogger.logToFile(getApplicationContext(), "bluetoothCommand run");

                    } catch (Exception ex) {
                        Log.e(TAG, String.format("BLE ERROR: Command exception for device '%s'"), ex);
                        Log.d("TAGG", String.format("BLE ERROR: Command exception for device '%s'"), ex);
                        //FileLogger.logToFile(getApplicationContext(), String.format("BLE ERROR: Command exception for device '%s'"));
                    }
                }
            });
        }
    }

    private void retryCommand() {
        Log.d("TAGG", "retryCommand called");
        //FileLogger.logToFile(getApplicationContext(), "retryCommand called");

        commandQueueBusy = false;
        Runnable currentCommand = commandQueue.peek();
        if (currentCommand != null) {
            if (retries >= MAX_TRIES) {
                // Max retries reached, give up on this one and proceed
                Log.v(TAG, "Max number of tries reached");
                Log.d("TAGG", "Max number of tries reached");
                //FileLogger.logToFile(getApplicationContext(), "Max number of tries reached");
                commandQueue.poll();
            } else {
                Log.d("TAGG", "retrying");
                //FileLogger.logToFile(getApplicationContext(), "retrying");
                isRetrying = true;
            }
        }
        nextCommand();
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (status == GATT_SUCCESS) {
                if (isBonded) {
                    //isBonded = false;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "BLE Connected to GATT server.");
                        Log.d("TAGG", "BLE Connected to GATT server.");
                        FileLogger.logToFile(getApplicationContext(), "BLE Connected to GATT server.");
                        intentAction = ACTION_GATT_CONNECTED;
                        mConnectionState = STATE_CONNECTED;
                        broadcastUpdate(intentAction);
                        //mBluetoothGatt = gatt;
                        // Attempts to discover services after successful connection.
                        if (status == GATT_SUCCESS) {
                            Log.i(TAG, "BLE Attempting to start service discovery:");
                            //Log.d("TAGG", "BLE Attempting to start service discovery");
                            FileLogger.logToFile(getApplicationContext(), "BLE Attempting to start service discovery");

                            boolean result = commandQueue.add(new Runnable() {
                                @Override
                                public void run() {
                                    if (!mBluetoothGatt.discoverServices()) {
                                        Log.e(TAG, "BLE ERROR: GATT Service discovery failed");
                                        Log.d("TAGG", "BLE ERROR: GATT Service discovery failed");
                                        //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: GATT Service discovery failed");
                                        completedCommand();
                                    } else {
                                        Log.i(TAG, "BLE INFO: Service Discovery Requested");
                                        Log.d("TAGG", "BLE Service Discovery Requested");
                                        //FileLogger.logToFile(getApplicationContext(), "BLE Service Discovery Requested");
                                        retries++;
                                    }
                                }
                            });
                            if (result) {
                                Log.d("TAGG", "commandQueue added");
                                //FileLogger.logToFile(getApplicationContext(), "commandQueue added");
                                nextCommand();
                            } else {
                                Log.d("TAGG", "commandQueue not added");
                                //FileLogger.logToFile(getApplicationContext(), "commandQueue not added");
                                Log.e(TAG, "BLE ERROR: Could not enqueue read characteristic command");
                            }
                        } else {
                            if (status == BluetoothGatt.GATT_READ_NOT_PERMITTED) {
                                Log.e(TAG, "BLE ERROR: GATT read operation is not permitted");
                                Log.d("TAGG", "BLE ERROR: GATT read operation is not permitted");
                                //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: GATT read operation is not permitted");
                            } else {
                                if (status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED) {
                                    Log.e(TAG, "BLE ERROR: GATT write operation is not permitted");
                                    Log.d("TAGG", "BLE ERROR: GATT write operation is not permitted");
                                    //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: GATT write operation is not permitted");
                                } else {
                                    if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                                        Log.e(TAG, "BLE ERROR: Insufficient authentication for a given operation");
                                        Log.d("TAGG", "BLE ERROR: Insufficient authentication for a given operation");
                                        //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Insufficient authentication for a given operation");
                                    } else {
                                        if (status == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED) {
                                            Log.e(TAG, "BLE ERROR: The given request is not supported");
                                            Log.d("TAGG", "BLE ERROR: The given request is not supported");
                                            //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: The given request is not supported");
                                        } else {
                                            Log.e(TAG, "BLE ERROR: A GATT operation failed");
                                            Log.d("TAGG", "BLE ERROR: A GATT operation failed");
                                            //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: A GATT operation failed");
                                        }
                                    }
                                }
                            }
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        intentAction = ACTION_GATT_DISCONNECTED;
                        mConnectionState = STATE_DISCONNECTED;
                        Log.i(TAG, "BLE Disconnected from GATT server.");
                        Log.d("TAGG", "BLE Disconnected from GATT server.");
                        FileLogger.logToFile(getApplicationContext(), "BLE Disconnected from GATT server.");
                        broadcastUpdate(intentAction);
                        close();
                        //mBluetoothGatt.close();
                        //mBluetoothGatt = null;
                    } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                        Log.i(TAG, "INFO: BLE Connecting");
                        Log.d("TAGG", "BLE Connecting");
                        //FileLogger.logToFile(getApplicationContext(), "BLE Connecting");
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
                        Log.d("TAGG", "BLE Disconnecting");
                        Log.i(TAG, "INFO: BLE Disconnecting");
                        //FileLogger.logToFile(getApplicationContext(), "BLE Disconnecting");
                    }
                }

            } else {

                if (isBonded) retryCommand();

            }


        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "BLE onServicesDiscovered: status " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("TAGG", "BLE SERVICES_DISCOVERED");
                //FileLogger.logToFile(getApplicationContext(), "BLE SERVICES_DISCOVERED");
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                final List<BluetoothGattService> services = gatt.getServices();

                boolean found = false;
                //List<BluetoothGattCharacteristic> discoveredCharacteristics = new ArrayList<>();
                for (BluetoothGattService service : services) {

                    Log.d(TAG, "onServicesDiscovered: " + service.getUuid().toString().toUpperCase());
                    Log.d(TAG, "onServicesDiscovered: " + SERVICE_UUID.toString());
                    if (SERVICE_UUID.toString().equals(service.getUuid().toString())) {
                        Log.d(TAG, "BLE INFO: Service Discovery Finished");
                        Log.d("TAGG", "BLE SERVICE_UUID FOUND: " + SERVICE_UUID);
                        //FileLogger.logToFile(getApplicationContext(), "BLE SERVICE_UUID FOUND: " + SERVICE_UUID);
                        found = true;
                        //discoveredCharacteristics.addAll(service.getCharacteristics());
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
                            Log.e(TAG, "BLE SUCCESS: RX Found");
                            Log.e(TAG, "BLE SUCCESS: Notifiable/Indictable");
                            Log.d("TAGG", "RX Found(Notifiable/Indictable): " + rx);
                            //FileLogger.logToFile(getApplicationContext(), "RX Found(Notifiable/Indictable): " + rx);
                        }

                        if (isCharacteristicWritable(characteristic)) {
                            tx = characteristic;
                            Log.e(TAG, "BLE SUCCESS: TX Found");
                            Log.e(TAG, "BLE SUCCESS: Writable");
                            Log.d("TAGG", "TX Found(Writable): " + tx);
                            //FileLogger.logToFile(getApplicationContext(), "TX Found(Writable): " + tx);
                        }

                        if (isCharacteristicReadable(characteristic)) {
                            Log.e(TAG, "BLE SUCCESS: Readable");
                            Log.d("TAGG", "Readable: true");
                            //FileLogger.logToFile(getApplicationContext(), "Readable: true");
                        }
                    }

                    if (rx != null) {
                        Log.d(TAG, "BLE INFO: Setting indication on RX possible");
                        // Setup notifications on RX characteristic changes (i.e. data received).
                        // First call setCharacteristicNotification to enable notification.
                        if (!gatt.setCharacteristicNotification(rx, true)) {
                            Log.e(TAG, "BLE ERROR: Couldn't set notifications for RX characteristic!");
                            Log.d("TAGG", "BLE ERROR: Couldn't set notifications for RX characteristic! " + rx);
                            //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Couldn't set notifications for RX characteristic! " + rx);
                            //errorEvent("ERROR: Couldn't set notifications for RX characteristic!","103");
                        } else {
                            // Next update the RX characteristic's client descriptor to enable notifications.
                            if (rx.getDescriptor(UUID.fromString(CLIENT_UUID)) != null) {
                                Log.d("TAGG", "CLIENT_UUID " + CLIENT_UUID);
                                //FileLogger.logToFile(getApplicationContext(), "CLIENT_UUID " + CLIENT_UUID);
                                BluetoothGattDescriptor desc = rx.getDescriptor(UUID.fromString(CLIENT_UUID));
                                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                mBluetoothGatt = gatt;
                                boolean result = commandQueue.add(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!mBluetoothGatt.writeDescriptor(desc)) {
                                            Log.e(TAG, "BLE ERROR: Couldn't write RX client descriptor value!");
                                            Log.d("TAGG", "BLE ERROR: Couldn't write RX client descriptor value!");
                                            //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Couldn't write RX client descriptor value!");
                                            completedCommand();
                                        } else {
                                            Log.d(TAG, "BLE SUCCESS: RX Notifications subscribed");
                                            Log.d("TAGG", "RX Notifications subscribed");
                                            //FileLogger.logToFile(getApplicationContext(), "RX Notifications subscribed");
                                            retries++;
                                        }
                                    }
                                });

                                if (result) {
                                    Log.d("TAGG", "commandQueue added");
                                    //FileLogger.logToFile(getApplicationContext(), "commandQueue added");
                                    nextCommand();
                                } else {
                                    Log.e(TAG, "ERROR: Could not enqueue read characteristic command");
                                    Log.d("TAGG", "BLE ERROR: Could not enqueue read characteristic command");
                                    //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Could not enqueue read characteristic command");
                                }

                            } else {
                                Log.e(TAG, "BLE ERROR: Couldn't get RX client descriptor!");
                                Log.d("TAGG", "BLE ERROR: Couldn't get RX client descriptor!");
                                //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Couldn't get RX client descriptor!");
                            }
                        }
                    }

                    if (tx != null) {
                        if (isCharacteristicIndicable(tx)) {
                            Log.d(TAG, "BLE INFO: Setting indication on TX possible");
                            // Setup notifications on TX characteristic changes (i.e. data sent).
                            // First call setCharacteristicIndication to enable indication.
                            if (!gatt.setCharacteristicNotification(tx, true)) {
                                Log.e(TAG, "BLE ERROR: Couldn't set indications for TX characteristic!");
                                Log.d("TAGG", "BLE ERROR: Couldn't set indications for TX characteristic!");
                                //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Couldn't set indications for TX characteristic!");
                                //errorEvent("ERROR: Couldn't set indications for TX characteristic!","103");
                            } else {
                                // Next update the TX characteristic's client descriptor to enable indications.
                                if (tx.getDescriptor(UUID.fromString(CLIENT_UUID)) != null) {
                                    Log.d("TAGG", "CLIENT_UUID " + CLIENT_UUID);
                                    //FileLogger.logToFile(getApplicationContext(), "CLIENT_UUID " + CLIENT_UUID);
                                    BluetoothGattDescriptor desc = tx.getDescriptor(UUID.fromString(CLIENT_UUID));
                                    desc.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);

                                    mBluetoothGatt = gatt;
                                    boolean result = commandQueue.add(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!mBluetoothGatt.writeDescriptor(desc)) {
                                                Log.e(TAG, "BLE ERROR: Couldn't write TX client descriptor value!");
                                                Log.d("TAGG", "BLE ERROR: Couldn't write TX client descriptor value!");
                                                //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Couldn't write TX client descriptor value!");
                                                completedCommand();
                                            } else {
                                                Log.d(TAG, "BLE SUCCESS: TX Notifications subscribed");
                                                Log.d("TAGG", "TX Notifications subscribed");
                                                //FileLogger.logToFile(getApplicationContext(), "TX Notifications subscribed");
                                                retries++;
                                            }
                                        }
                                    });

                                    if (result) {
                                        Log.d("TAGG", "commandQueue added");
                                        //FileLogger.logToFile(getApplicationContext(), "commandQueue added");
                                        nextCommand();
                                    } else {
                                        Log.e(TAG, "ERROR: Could not enqueue read characteristic command");
                                        Log.d("TAGG", "BLE ERROR: Could not enqueue read characteristic command!");
                                        //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Could not enqueue read characteristic command!");
                                    }

                                } else {
                                    Log.e(TAG, "BLE ERROR: Couldn't get TX client descriptor!");
                                    Log.d("TAGG", "BLE ERROR: Couldn't get TX client descriptor!");
                                    //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Couldn't get TX client descriptor!");
                                }
                            }
                        } else {
                            Log.e(TAG, "BLE WARNING: Cannot set indication on TX");
                            Log.d("TAGG", "BLE ERROR: Cannot set indication on TX! " + tx);
                            //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Cannot set indication on TX! " + tx);
                        }
                    }
                }

                completedCommand();
            } else {
                if (isBonded)
                    retryCommand();
                Log.d(TAG, "BLE INFO: onServicesDiscoveonConnectionStateChanged() received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "BLE onCharacteristicRead: characteristic " + characteristic);
            Log.d(TAG, "BLE onCharacteristicRead: status " + status);
            Log.d("TAGG", "onCharacteristicRead status " + status);
            //FileLogger.logToFile(getApplicationContext(), "onCharacteristicRead status " + status);
            if (status == GATT_SUCCESS) {
                Log.d("TAGG", "onCharacteristicRead GATT_SUCCESS " + GATT_SUCCESS);
                //FileLogger.logToFile(getApplicationContext(), "onCharacteristicRead GATT_SUCCESS " + GATT_SUCCESS);
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.d("TAGG", "onCharacteristicWrite status " + status);
            //FileLogger.logToFile(getApplicationContext(), "onCharacteristicWrite status " + status);
            if (status == GATT_SUCCESS) {
                Log.d(TAG, "BLE INFO: onCharacteristicWrite Success");
                Log.d("TAGG", "onCharacteristicWrite GATT_SUCCESS " + GATT_SUCCESS);
                //FileLogger.logToFile(getApplicationContext(), "onCharacteristicWrite GATT_SUCCESS " + GATT_SUCCESS);
                completedCommand();

            } else {
                Log.e(TAG, "BLE ERROR: onCharacteristicWrite failed");
                Log.d("TAGG", "BLE ERROR: onCharacteristicWrite failed");
                //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: onCharacteristicWrite failed");
                retryCommand();

            }
        }

        @Override
        public void onDescriptorRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, int status, @NonNull byte[] value) {
            super.onDescriptorRead(gatt, descriptor, status, value);
            Log.d(TAG, "BLE INFO: onDescriptorRead status" + status);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            if (status == GATT_SUCCESS) {
                //mBluetoothGatt = gatt;
                boolean result = commandQueue.add(new Runnable() {
                    @Override
                    public void run() {
                        if (!mBluetoothGatt.requestMtu(517)) {
                            Log.e(TAG, "BLE ERROR: Couldn't equest MTU!");
                            Log.d("TAGG", "BLE ERROR: Couldn't request MTU! 517");
                            //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Couldn't request MTU! 517");
                            completedCommand();
                        } else {
                            Log.d(TAG, "BLE SUCCESS: MTU Requested Successfully!");
                            Log.d("TAGG", "MTU Requested Successfully");
                            //FileLogger.logToFile(getApplicationContext(), "MTU Requested Successfully");
                            retries++;
                        }
                    }
                });

                if (result) {
                    Log.d("TAGG", "commandQueue added");
                    //FileLogger.logToFile(getApplicationContext(), "commandQueue added");
                    nextCommand();
                } else {
                    Log.e(TAG, "BLE ERROR: Could not enqueue read characteristic command");
                    Log.d("TAGG", "BLE ERROR: Could not enqueue read characteristic command!");
                    //FileLogger.logToFile(getApplicationContext(), "BLE ERROR: Could not enqueue read characteristic command!");
                }

                completedCommand();

            } else {

                if (isBonded) retryCommand();

            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            Log.d(TAG, "BLE SUCCESS: Negotiated MTU " + mtu);
            Log.d("TAGG", "Negotiated MTU: " + mtu);
            //FileLogger.logToFile(getApplicationContext(), "Negotiated MTU: " + mtu);
            if (status == GATT_SUCCESS) {
                //send(SampleGattAttributes.hexStringToByteArray(byteString));
                Log.d("TAGG", "onMtuChanged GATT_SUCCESS " + GATT_SUCCESS);
                //FileLogger.logToFile(getApplicationContext(),  "onMtuChanged GATT_SUCCESS " + GATT_SUCCESS);
                completedCommand();
                //deviceConnected(mac_address_str);
            } else {

                if (isBonded) retryCommand();

            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "BLE onCharacteristicChanged: characteristic " + characteristic);
            Log.d("TAGG", "onCharacteristicChanged " + characteristic);
            //FileLogger.logToFile(getApplicationContext(),  "onCharacteristicChanged " + characteristic);

            ioBuffer = characteristic.getValue();
            String byteArrayToStr = new String(ioBuffer, StandardCharsets.US_ASCII);
            Log.d(TAG, "BLE Received data of length " + byteArrayToStr.length());
            Log.d("TAGG", "BLE Received data " + byteArrayToStr);
            //FileLogger.logToFile(getApplicationContext(),  "BLE Received data " + byteArrayToStr);
            Log.d("TAGG", "BLE Received data length" + byteArrayToStr.length());
            //FileLogger.logToFile(getApplicationContext(),  "BLE Received data length" + byteArrayToStr.length());
            ioBuffer = null;
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            completedCommand();
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "BLE Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "BLE Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("BLE Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
        } else {
            // For all other profiles, writes the data formatted in HEX.
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for (byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                intent.putExtra(EXTRA_DATA, stringBuilder.toString());
            }
        }
        sendBroadcast(intent);
    }

    public void send(final byte[] dataHexFormat) {
        ioBuffer = null;
        if (mBluetoothGatt == null || uart == null || rx == null || tx == null) {
            return;
        }

        Log.d(TAG, "BLE INFO: send() via " + tx.getUuid().toString().toUpperCase());
        Log.d(TAG, "BLE INFO: send() via " + new String(dataHexFormat, StandardCharsets.UTF_8));

        if (!isCharacteristicWritable(tx)) {
            Log.e(TAG, "BLE ERROR: NO WRITE POSSIBLE");
            return;
        }

        if (!isCharacteristicReadable(tx)) {
            Log.e(TAG, "BLE ERROR: NO READ POSSIBLE");
        }

        if ((tx.getProperties() & PROPERTY_WRITE_NO_RESPONSE) != 0) {
            tx.setWriteType(WRITE_TYPE_NO_RESPONSE);
        } else {
            tx.setWriteType(WRITE_TYPE_DEFAULT);
        }

        tx.setValue(dataHexFormat);

        boolean result = commandQueue.add(new Runnable() {
            @Override
            public void run() {
                if (!mBluetoothGatt.writeCharacteristic(tx)) {
                    Log.e(TAG, "BLE ERROR: GATT write failed");
                    completedCommand();
                } else {
                    Log.d(TAG, "BLE INFO: write Requested");
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

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        unregisterReceiver(mReceiver);
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "BLE Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "BLE Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.d("TAGG", "BLE BluetoothAdapter not initialized");
            //FileLogger.logToFile(getApplicationContext(),  "BLE ERROR: BluetoothAdapter not initialized");
            Log.w(TAG, "BLE BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        /*if (address.equals(mBluetoothDeviceAddress) && mBluetoothGatt != null) {
            Log.d(TAG, "BLE Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }*/

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.d("TAGG", "BLE Device not found.  Unable to connect.");
            //FileLogger.logToFile(getApplicationContext(),  "BLE ERROR: Device not found.  Unable to connect.");
            Log.w(TAG, "BLE Device not found.  Unable to connect.");
            return false;
        }
        if (device != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            registerReceiver(mReceiver, filter);
            Log.d("TAGG", "BroadcastReceiver Registered");
            //FileLogger.logToFile(getApplicationContext(),  "BLE BroadcastReceiver Registered");
            removeBond(device);
            mBluetoothDeviceAddress = address;
        }

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        //mBluetoothGatt = device.connectGatt(this, false, mGattCallback);

        return true;
    }

    private void removeBond(BluetoothDevice device) {
        Method method = null;

        try {
            method = device.getClass().getMethod("removeBond", (Class[]) null);
            boolean result = (boolean) method.invoke(device, (Object[]) null);
            isBonded = false;
            Log.v("RemoveBond Status", " " + result);
            Log.d("TAGG", "Remove bond " + result);
            //FileLogger.logToFile(getApplicationContext(),  "BLE Remove bond " + result);

            if (!result) {

                if (mBluetoothGatt != null) {
                    mBluetoothGatt.close();
                }
                isBonded = false;
                mBluetoothGatt = null;
                Log.d(TAG, "BLE Trying to create a new connection.");
                Log.d("TAGG", "BLE Trying to create a new connection.");
                //FileLogger.logToFile(getApplicationContext(),  "BLE Trying to create a new connection.");
                mBluetoothGatt = device.connectGatt(getApplicationContext(), false, mGattCallback);
                mConnectionState = STATE_CONNECTING;
            }
        } catch (NoSuchMethodException e) {
            Log.v("error : ", "NoSuchMethodException");
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            Log.v("error : ", "InvocationTargetException");
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            Log.v("error : ", "IllegalAccessException");
            throw new RuntimeException(e);
        }
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BLE BluetoothAdapter not initialized");
            return;
        }

        mBluetoothGatt.disconnect();
        //mBluetoothGatt.close();
        //close();
        //mBluetoothGatt = null;
//        String intentAction = ACTION_GATT_DISCONNECTED;
//        mConnectionState = STATE_DISCONNECTED;
//        Log.i(TAG, "BLE Disconnected from GATT server.");
//        broadcastUpdate(intentAction);
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BLE BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    public void writeCharacteristic(BluetoothGattCharacteristic characteristic, String data) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BLE BluetoothAdapter not initialized");
            return;
        }
        send(hexStringToByteArray(data));
        //mBluetoothGatt.writeCharacteristic(tx, hexStringToByteArray(data), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled        If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement.
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
