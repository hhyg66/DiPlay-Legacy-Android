package com.shilapi.xcertplay.transport;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

public final class NforetekCallbackBinder extends Binder {
    public interface Handler {
        void onReady();
        void onState(String address, String detail, int state, int extra);
        void onError(String address, int error);
        void onConnectedList(int result, String[] addresses, String[] names);
        void onData(String address, byte[] data);
        void onSend(String address, int result);
        void onAppleIapAuth(String address);
    }

    private final String descriptor;
    private final Handler handler;

    public NforetekCallbackBinder(String descriptor, Handler handler) {
        this.descriptor = descriptor;
        this.handler = handler;
        attachInterface(null, descriptor);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply.writeString(descriptor);
            return true;
        }
        if (code < 1 || code > 7) {
            return super.onTransact(code, data, reply, flags);
        }
        data.enforceInterface(descriptor);
        switch (code) {
            case 1:
                handler.onReady();
                break;
            case 2:
                handler.onState(data.readString(), data.readString(), data.readInt(), data.readInt());
                break;
            case 3:
                handler.onError(data.readString(), data.readInt());
                break;
            case 4:
                handler.onConnectedList(data.readInt(), data.createStringArray(), data.createStringArray());
                break;
            case 5:
                handler.onData(data.readString(), data.createByteArray());
                break;
            case 6:
                handler.onSend(data.readString(), data.readInt());
                break;
            case 7:
                handler.onAppleIapAuth(data.readString());
                break;
        }
        reply.writeNoException();
        return true;
    }
}
