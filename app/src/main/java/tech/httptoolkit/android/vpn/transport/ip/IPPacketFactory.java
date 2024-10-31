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

package tech.httptoolkit.android.vpn.transport.ip;

import androidx.annotation.NonNull;

import tech.httptoolkit.android.vpn.transport.PacketHeaderException;

import java.nio.ByteBuffer;

/**
 * class for creating packet data, header etc related to IP
 * @author Borey Sao
 * Date: June 30, 2014
 */
public class IPPacketFactory {

	public static IPHeader createIPHeader(@NonNull ByteBuffer stream) throws PacketHeaderException {
		final byte versionAndHeaderLength = stream.get();
		final byte ipVersion = (byte) (versionAndHeaderLength >> 4);
		stream.rewind();

		if (ipVersion == 4) {
			return new IPv4Header(stream);
		} else {
			return new IPv6Header(stream);
		}
	}
}
