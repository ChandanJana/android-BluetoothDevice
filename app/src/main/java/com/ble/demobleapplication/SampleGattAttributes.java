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

import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This class includes a small subset of standard GATT attributes for demonstration purposes.
 */
public class SampleGattAttributes {
    private static HashMap<String, String> attributes = new HashMap();
    public static String HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb";
    public static String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";

    static {
        // Sample Services.
        attributes.put("0000180d-0000-1000-8000-00805f9b34fb", "Heart Rate Service");
        attributes.put("0000180a-0000-1000-8000-00805f9b34fb", "Device Information Service");
        // Sample Characteristics.
        attributes.put(HEART_RATE_MEASUREMENT, "Heart Rate Measurement");
        attributes.put("00002a29-0000-1000-8000-00805f9b34fb", "Manufacturer Name String");
    }

    public static String lookup(String uuid, String defaultName) {
        String name = attributes.get(uuid);
        return name == null ? defaultName : name;
    }

    public static boolean containsProperty(@NotNull BluetoothGattCharacteristic gattCharacteristic, int property) {
        return (gattCharacteristic.getProperties() & property) != 0;
    }

    public static boolean isReadable(@NotNull BluetoothGattCharacteristic gattCharacteristic) {
        return containsProperty(gattCharacteristic, BluetoothGattCharacteristic.PERMISSION_READ);
    }

    public static boolean isWritable(@NotNull BluetoothGattCharacteristic gattCharacteristic) {
        return containsProperty(gattCharacteristic, BluetoothGattCharacteristic.PROPERTY_WRITE);
    }

    public static boolean isWritableWithoutResponse(@NotNull BluetoothGattCharacteristic gattCharacteristic) {
        return containsProperty(gattCharacteristic, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
    }

    public static boolean isIndicatable(@NotNull BluetoothGattCharacteristic gattCharacteristic) {
        return containsProperty(gattCharacteristic, BluetoothGattCharacteristic.PROPERTY_INDICATE);
    }

    public static boolean isNotifiable(@NotNull BluetoothGattCharacteristic gattCharacteristic) {
        return containsProperty(gattCharacteristic, BluetoothGattCharacteristic.PROPERTY_NOTIFY);
    }

    public static boolean isReadable(@NotNull BluetoothGattDescriptor gattDescriptor) {
        return containsPermission(gattDescriptor, BluetoothGattDescriptor.PERMISSION_READ);
    }

    public static boolean isWritable(@NotNull BluetoothGattDescriptor gattDescriptor) {
        return containsPermission(gattDescriptor, BluetoothGattDescriptor.PERMISSION_WRITE);
    }

    public static boolean containsPermission(@NotNull BluetoothGattDescriptor gattDescriptor, int permission) {
        return (gattDescriptor.getPermissions() & permission) != 0;
    }

    public static String printProperties(@NotNull BluetoothGattCharacteristic gattCharacteristic) {
        List<String> stringList = new ArrayList();
        if (isReadable(gattCharacteristic)) {
            stringList.add("READABLE");
        }

        if (isWritable(gattCharacteristic)) {
            stringList.add("WRITABLE");
        }

        if (isWritableWithoutResponse(gattCharacteristic)) {
            stringList.add("WRITABLE WITHOUT RESPONSE");
        }

        if (isIndicatable(gattCharacteristic)) {
            stringList.add("INDICATABLE");
        }

        if (isNotifiable(gattCharacteristic)) {
            stringList.add("NOTIFIABLE");
        }

        if (stringList.isEmpty()) {
            stringList.add("EMPTY");
        }

        return stringList.toString();
    }

    @NotNull
    public static String printProperties(@NotNull BluetoothGattDescriptor gattDescriptor) {
        List<String> stringList = new ArrayList();
        if (isReadable(gattDescriptor)) {
            stringList.add("READABLE");
        }

        if (isWritable(gattDescriptor)) {
            stringList.add("WRITABLE");
        }

        if (stringList.isEmpty()) {
            stringList.add("EMPTY");
        }

        return stringList.toString();
    }

    public static boolean isCharacteristicWritable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    public static boolean isCharacteristicReadable(BluetoothGattCharacteristic pChar) {

        return (pChar.getProperties() & (BluetoothGattCharacteristic.PROPERTY_READ)) != 0;
    }

    public static boolean isCharacteristicNotifiable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
    }

    public static boolean isCharacteristicIndicable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
