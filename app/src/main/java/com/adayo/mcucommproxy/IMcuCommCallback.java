package com.adayo.mcucommproxy;

public interface IMcuCommCallback {
    void mcuCommCallback(byte[] data, char length);
}
