package com.harrysoft.androidbluetoothserial;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.ArrayMap;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.reactivex.Single;

/**
 * Implementation of BluetoothManager, package-private
 */
class BluetoothManagerImpl implements BluetoothManager {

    private final BluetoothAdapter adapter;

    private final Map<String, BluetoothSerialDevice> devices = new ArrayMap<>();

    /**
     * Package private constructor
     */
    BluetoothManagerImpl(BluetoothAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public List<BluetoothDevice> getPairedDevicesList() {
        return new ArrayList<>(adapter.getBondedDevices());
    }

    @Override
    public Single<BluetoothSerialDevice> openSerialDevice(String mac) {
        return openSerialDevice(mac, StandardCharsets.UTF_8);
    }

    @Override
    public Single<BluetoothSerialDevice> openSerialDevice(String mac, Charset charset) {
        if (devices.containsKey(mac)) {
            return Single.just(devices.get(mac));
        } else {
            return Single.fromCallable(() -> {
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(mac);
                    BluetoothSocket socket = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                    adapter.cancelDiscovery();
                    socket.connect();
                    BluetoothSerialDevice serialDevice = BluetoothSerialDevice.getInstance(mac, socket, charset);
                    devices.put(mac, serialDevice);
                    return serialDevice;
                } catch (Exception e) {
                    throw new BluetoothConnectException(e);
                }
            });
        }
    }

    @Override
    public void closeDevice(String mac) {
        BluetoothSerialDevice removedDevice = devices.remove(mac);
        if (removedDevice != null) {
            try {
                removedDevice.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void closeDevice(BluetoothSerialDevice device) {
        closeDevice(device.getMac());
    }

    @Override
    public void closeDevice(SimpleBluetoothDeviceInterface deviceInterface) {
        closeDevice(deviceInterface.getDevice().getMac());
    }

    @Override
    public void close() {
        for (Map.Entry<String, BluetoothSerialDevice> deviceEntry :  devices.entrySet()) {
            try {
                deviceEntry.getValue().close();
            } catch (Throwable ignored) {}
        }
        devices.clear();
    }
}
