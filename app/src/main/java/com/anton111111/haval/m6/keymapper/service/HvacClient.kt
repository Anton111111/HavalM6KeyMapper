package com.anton111111.haval.m6.keymapper.service

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

internal class HvacClient {
    fun pulse(key: String) {
        setBoolean(key, true)
    }

    fun setBoolean(key: String, value: Boolean): Boolean =
        transact(1, key, { writeInt(if (value) 1 else 0) }) { readInt() != 0 }

    fun setInt(key: String, value: Int): Boolean =
        transact(2, key, { writeInt(value) }) { readInt() != 0 }

    fun getBoolean(key: String): Boolean =
        transact(5, key) { readInt() != 0 }

    fun getInt(key: String): Int =
        transact(6, key) { readInt() }

    private fun <T> transact(
        code: Int,
        key: String,
        writeArgument: Parcel.() -> Unit = {},
        readResult: Parcel.() -> T,
    ): T {
        val binder = serviceBinder()
        if (!binder.isBinderAlive) throw RemoteException("HVAC Binder is dead")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(key)
            data.writeArgument()
            if (!binder.transact(code, data, reply, 0)) {
                throw RemoteException("HVAC service rejected transaction $code for $key")
            }
            reply.readException()
            return reply.readResult()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun serviceBinder(): IBinder {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        return getService.invoke(null, SERVICE_NAME) as? IBinder
            ?: throw RemoteException("HVAC service '$SERVICE_NAME' is unavailable")
    }

    private companion object {
        const val DESCRIPTOR = "com.adayo.hvacproxy.IHvacClientProxy"
        const val SERVICE_NAME = "adayo.hvac"
    }
}
