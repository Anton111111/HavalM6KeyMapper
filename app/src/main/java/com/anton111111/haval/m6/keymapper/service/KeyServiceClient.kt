package com.anton111111.haval.m6.keymapper.service

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.anton111111.haval.m6.keymapper.data.MapperSettings
import com.anton111111.haval.m6.keymapper.data.OemKeyCatalog

internal object KeyServiceClient {
    const val PACKAGE = "com.adayo.service.keyevent"
    const val SERVICE = "$PACKAGE.service.KeyEventService"
    private const val DESCRIPTOR = "com.adayo.proxy.keyevent.binder.service.IKeyEventService"
    private const val FILTER_LEVEL = 10

    fun whitelist(catalog: OemKeyCatalog, settings: MapperSettings): Array<String> {
        val reserved = setOf(catalog.callingLevel, catalog.cameraLevel, catalog.powerLevel)
        require(FILTER_LEVEL !in reserved) {
            "OEM call/camera/power handling already uses key filter level $FILTER_LEVEL"
        }
        val blockedNames = settings.rules.values
            .filter { it.overridesStock() }
            .mapNotNull { rule -> catalog.buttons.firstOrNull { it.value == rule.value }?.oemName }
            .toSet()
        val result = catalog.allButtonNames - blockedNames
        require(result.isNotEmpty()) { "OEM key whitelist would be empty" }
        return result.toTypedArray()
    }

    @Throws(RemoteException::class)
    fun disable(binder: IBinder, whitelist: Array<String>) {
        transact(binder, 5, whitelist)
    }

    @Throws(RemoteException::class)
    fun restore(binder: IBinder) {
        transact(binder, 6, null)
    }

    private fun transact(binder: IBinder, code: Int, whitelist: Array<String>?) {
        if (!binder.isBinderAlive) throw RemoteException("OEM key service Binder is dead")
        if (binder.interfaceDescriptor != DESCRIPTOR) {
            throw RemoteException("Unexpected OEM key service Binder descriptor")
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(FILTER_LEVEL)
            if (code == 5) data.writeStringArray(whitelist)
            if (!binder.transact(code, data, reply, 0)) {
                throw RemoteException("OEM key service rejected transaction $code")
            }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
