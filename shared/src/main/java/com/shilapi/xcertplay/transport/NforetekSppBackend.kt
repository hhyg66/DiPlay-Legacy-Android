package com.shilapi.xcertplay.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class NforetekSppBackend(private val context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "NforetekSpp"
        private const val PKG = "com.nforetek.bt"
        private const val SERVICE = "com.nforetek.bt.service.NfServiceSpp"
        private const val DESC = "com.nforetek.bt.aidl.INfCommandSpp"
        private const val CALLBACK_DESC = "com.nforetek.bt.aidl.INfCallbackSpp"
        private const val T_READY = 1
        private const val T_REGISTER = 2
        private const val T_UNREGISTER = 3
        private const val T_CONNECT = 4
        private const val T_DISCONNECT = 5
        private const val T_LIST = 6
        private const val T_CONNECTED = 7
        private const val T_SEND = 8
        private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        fun tryOpen(context: Context, timeoutMs: Long = 3000): NforetekSppBackend? {
            val b = NforetekSppBackend(context.applicationContext)
            if (!b.bind(timeoutMs)) {
                b.close()
                return null
            }
            return b
        }
    }

    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private val addresses = linkedSetOf<String>()
    private var latch = CountDownLatch(1)
    @Volatile private var closed = false
    @Volatile private var bound = false
    @Volatile private var command: IBinder? = null
    @Volatile private var activeAddress: String? = null

    private val callback = NforetekCallbackBinder(CALLBACK_DESC, object : NforetekCallbackBinder.Handler {
        override fun onReady() {
            Log.i(TAG, "SPP service ready")
        }

        override fun onState(address: String?, detail: String?, state: Int, extra: Int) {
            Log.i(TAG, "SPP state address=" + address + " detail=" + detail + " state=" + state + " extra=" + extra)
            if (address != null) synchronized(lock) {
                if (state > 0) addresses.add(address.uppercase(Locale.US))
                else addresses.remove(address.uppercase(Locale.US))
                lock.notifyAll()
            }
        }

        override fun onError(address: String?, error: Int) {
            Log.w(TAG, "SPP error address=" + address + " error=" + error)
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onConnectedList(result: Int, list: Array<String>?, names: Array<String>?) {
            val addressesList = list.orEmpty()
            val namesList = names.orEmpty()
            Log.i(TAG, "SPP list result=" + result + " addresses=" + addressesList.contentToString() + " names=" + namesList.contentToString())
            synchronized(lock) {
                addresses.clear()
                addressesList.filter { validMac(it) }.forEach { addresses.add(it.uppercase(Locale.US)) }
                lock.notifyAll()
            }
        }

        override fun onData(address: String?, bytes: ByteArray?) {
            if (bytes != null && bytes.isNotEmpty()) synchronized(lock) {
                if (activeAddress == null || address.equals(activeAddress, true)) {
                    queue.addLast(bytes)
                    lock.notifyAll()
                }
            }
        }

        override fun onSend(address: String?, result: Int) {
            Log.d(TAG, "SPP send address=" + address + " result=" + result)
        }

        override fun onAppleIapAuth(address: String?) {
            Log.i(TAG, "SPP Apple iAP auth request address=" + address)
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            command = service
            Log.i(TAG, "bound " + name + " descriptor=" + runCatching { service.interfaceDescriptor }.getOrNull())
            latch.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            command = null
            Log.w(TAG, "service disconnected " + name)
            synchronized(lock) { lock.notifyAll() }
        }
    }

    private fun bind(timeoutMs: Long): Boolean {
        val intent = Intent().setComponent(ComponentName(PKG, SERVICE))
        bound = runCatching { context.bindService(intent, connection, 0) }
            .onFailure { Log.e(TAG, "bindService failed", it) }
            .getOrDefault(false)
        if (!bound) return false
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        val ok = transactBool(T_REGISTER) { it.writeStrongBinder(callback) }
        val ready = transactBool(T_READY)
        Log.i(TAG, "register=" + ok + " ready=" + ready)
        return ok
    }

    fun connectedAddresses(timeoutMs: Long = 1500): List<String> {
        synchronized(lock) { addresses.clear() }
        transactVoid(T_LIST)
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (addresses.isEmpty() && System.currentTimeMillis() < end && !closed) {
                lock.wait((end - System.currentTimeMillis()).coerceAtLeast(1))
            }
            return addresses.toList()
        }
    }

    fun connect(address: String, timeoutMs: Long = 8000): BlockingDuplexByteStream {
        val normalized = address.uppercase(Locale.US)
        synchronized(lock) {
            activeAddress = normalized
            queue.clear()
        }
        if (!transactBool(T_CONNECT) { it.writeString(normalized) }) {
            throw IOException("NForetek reqSppConnect returned false")
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (transactBool(T_CONNECTED) { it.writeString(normalized) }) return Stream(normalized)
            Thread.sleep(150)
        }
        throw IOException("NForetek SPP connection timeout: " + normalized)
    }

    private inner class Stream(private val address: String) : BlockingDuplexByteStream {
        @Volatile private var streamClosed = false

        override fun send(data: ByteArray) {
            if (!streamClosed && !closed) transactVoid(T_SEND) {
                it.writeString(address)
                it.writeByteArray(data)
            }
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (streamClosed || closed) return ByteArray(0)
            synchronized(lock) {
                val end = System.currentTimeMillis() + timeoutMillis
                while (queue.isEmpty() && !streamClosed && !closed) {
                    val remaining = end - System.currentTimeMillis()
                    if (remaining <= 0) return null
                    lock.wait(remaining)
                }
                if (queue.isEmpty()) return ByteArray(0)
                val first = queue.removeFirst()
                if (first.size <= maxBytes) return first
                queue.addFirst(first.copyOfRange(maxBytes, first.size))
                return first.copyOfRange(0, maxBytes)
            }
        }

        override fun close() {
            if (streamClosed) return
            streamClosed = true
            runCatching { transactBool(T_DISCONNECT) { it.writeString(address) } }
            synchronized(lock) { queue.clear(); lock.notifyAll() }
        }
    }

    private fun transactBool(code: Int, writer: ((Parcel) -> Unit)? = null): Boolean {
        val remote = command ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC)
            writer?.invoke(data)
            if (!remote.transact(code, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (e: RemoteException) {
            Log.e(TAG, "Binder bool code=" + code + " failed", e)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactVoid(code: Int, writer: ((Parcel) -> Unit)? = null) {
        val remote = command ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            writer?.invoke(data)
            if (remote.transact(code, data, reply, 0)) reply.readException()
        } catch (e: RemoteException) {
            Log.e(TAG, "Binder void code=" + code + " failed", e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override fun close() {
        if (closed) return
        runCatching { transactBool(T_UNREGISTER) { it.writeStrongBinder(callback) } }
        closed = true
        if (bound) runCatching { context.unbindService(connection) }
        synchronized(lock) { queue.clear(); lock.notifyAll() }
    }

    private fun validMac(value: String): Boolean =
        MAC.matches(value) && value != "00:00:00:00:00:00" && value != "02:00:00:00:00:00"

}
