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

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Selector;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

import tech.httptoolkit.android.TagKt;
import org.distrinet.lanshield.crashreport.CrashReporterKt;

/**
 * write packet data back to VPN client stream. This class is thread safe.
 * @author Borey Sao
 * Date: May 22, 2014
 */
public class ClientPacketWriter implements Runnable {

	private final String TAG = TagKt.getTAG(this);

	private final FileOutputStream clientWriter;

	// Defensive upper bound on a single packet to the TUN. Real traffic is well under the MTU
	// (TCP segments are capped at the MSS, oversized UDP is dropped upstream in SocketChannelReader),
	// so this should never trigger; if it does it's a logic error we drop rather than crash on.
	private static final int MAX_WRITE_PACKET_SIZE = 30000;

	private volatile boolean shutdown = false;
	private volatile boolean alreadyReportedOversize = false;
	private final BlockingDeque<byte[]> packetQueue = new LinkedBlockingDeque<>();

	public ClientPacketWriter(FileOutputStream clientWriter) {
		this.clientWriter = clientWriter;
	}

	public void write(byte[] data) {
		if (data.length > MAX_WRITE_PACKET_SIZE) {
			// Drop instead of throwing: this runs on the NIO thread, and an uncaught Error here
			// would tear down the whole forwarding engine.
			Log.w(TAG, "Dropping oversized packet (" + data.length + " bytes)");
			if (!alreadyReportedOversize) {
				alreadyReportedOversize = true;
				CrashReporterKt.getCrashReporter().recordException(
						new RuntimeException("Dropped oversized packet to TUN: " + data.length + " bytes"));
			}
			return;
		}
		packetQueue.addLast(data);
	}

	public void shutdown() {
		this.shutdown = true;
	}

	@Override
	public void run() {
		while (!this.shutdown) {
			try {
				byte[] data = this.packetQueue.take();
				try {
					this.clientWriter.write(data);
				} catch (IOException e) {
					Log.e(TAG, "Error writing " + data.length + " bytes to the VPN");
					e.printStackTrace();

					this.packetQueue.addFirst(data); // Put the data back, so it's resent
					Thread.sleep(10); // Add an arbitrary tiny pause, in case that helps
				}
			} catch (InterruptedException e) { }
		}
	}
}
