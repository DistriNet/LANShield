package tech.httptoolkit.android.vpn.transport.ip;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.net.InetAddress;

import tech.httptoolkit.android.vpn.transport.PacketHeaderException;

public class IPAddress {
    private byte[] address;

    public IPAddress(byte[] address) {
        this.address = address;
    }

    public IPAddress(@NonNull ByteBuffer stream, int length) throws PacketHeaderException {
        address = new byte[length];
        stream.get(address, 0, length);
    }

    public byte[] getBytes() {
        return this.address;
    }

    public IPAddress clone(){
        return new IPAddress(address.clone());
    }

    public String toString() {
        // return InetAddress.getByAddress(address).toString();
        if (address.length == 4) {
            return  ((int)address[0] & 0xff) + "." +
                    ((int)address[1] & 0xff) + "." +
                    ((int)address[2] & 0xff) + "." +
                    ((int)address[3] & 0xff);
        } else {
            String string = "";
            for (int i = 0; i < 16; i += 2) {
                string += String.format("%04x", ((address[i] & 0xff) << 8) + (address[i + 1] & 0xff));
                if (i + 2 < 16)
                    string += ":";
            }
            return string;
        }
    }
}
