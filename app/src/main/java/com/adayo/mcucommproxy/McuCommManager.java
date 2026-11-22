package com.adayo.mcucommproxy;

public final class McuCommManager {
    private McuCommManager() {}

    public static native boolean connectMcuCommServer();
    public static native boolean mcuCommSendcmd(byte[] data, char length);
    public static native boolean registMcuCommCallback(IMcuCommCallback callback, byte functionId);
    public static native boolean unregistMcuCommCallback(IMcuCommCallback callback, byte functionId);
}
