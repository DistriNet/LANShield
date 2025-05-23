/*
 *  Copyright 2014 AT&T
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

package tech.httptoolkit.android.vpn;

import androidx.annotation.NonNull;

import tech.httptoolkit.android.vpn.transport.ip.IPHeader;
import tech.httptoolkit.android.vpn.transport.ITransportHeader;
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader;
import tech.httptoolkit.android.vpn.transport.udp.UDPHeader;

/**
 * Data structure that encapsulate both IPv4Header and TCPHeader
 * @author Borey Sao
 * Date: May 27, 2014
 */
public class Packet {
	@NonNull private final IPHeader ipHeader;
	@NonNull private final ITransportHeader transportHeader;
	@NonNull private final byte[] buffer;

	public Packet(@NonNull IPHeader ipHeader, @NonNull ITransportHeader transportHeader, @NonNull byte[] data) {
		this.ipHeader = ipHeader;
		this.transportHeader = transportHeader;
		int transportLength;
		if (transportHeader instanceof TCPHeader) {
			transportLength = ((TCPHeader) transportHeader).getDataOffset();
		} else if (transportHeader instanceof UDPHeader) {
			transportLength = 8;
		}
		buffer = data;
	}

	@NonNull
	public ITransportHeader getTransportHeader() {
		return transportHeader;
	}

	public int getSourcePort() {
		return transportHeader.getSourcePort();
	}

	public int getDestinationPort() {
		return transportHeader.getDestinationPort();
	}

	@NonNull
	public IPHeader getIpHeader() {
		return ipHeader;
	}

	/**
	 * the whole packet data as an array of byte
	 * @return byte[]
	 */
	@NonNull
	public byte[] getBuffer() {
		return buffer;
	}

}
