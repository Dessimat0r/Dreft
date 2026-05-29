package org.deftserver.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.deftserver.web.AsyncCallback;
import org.deftserver.web.AsyncResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AsynchronousSocketTest {

	public static final String HOST = "localhost";
	private int port;
	private IOLoop ioLoop;
	private AsynchronousSocket socket;
	private CountDownLatch latch;

	@Before
	public void setup() throws Exception {
		ioLoop = new IOLoop();
		SelectableChannel channel = SocketChannel.open();
		channel.configureBlocking(false);
		socket = new AsynchronousSocket(ioLoop, channel);
		latch = new CountDownLatch(3);	// 3 op/callbacks (connect, write, read)

		// start the IOLoop from a new thread so we dont block this test.
		Thread.ofPlatform().start(() -> ioLoop.start());
		Thread.sleep(300);	// hack to avoid SLF4J warning
		
		final CountDownLatch latch = new CountDownLatch(1);	
		final ServerSocket server = new ServerSocket(0);
		port = server.getLocalPort();
		Thread.ofPlatform().start(() -> {
			InputStream is = null;
			DataOutputStream os = null;
			try {
				System.out.println("waiting for client...");
				latch.countDown();
				Socket client = server.accept();
				System.out.println("client connected..");
				is = client.getInputStream();
				os = new DataOutputStream(client.getOutputStream());

				java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
				String recevied = reader.readLine();
				System.out.println("about to send: " + recevied);
				os.writeBytes(recevied.toUpperCase());
				System.out.println("sent data to client, shutdown server...");
				server.close();
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				closeQuietly(is, os);
			}
		});
		
		latch.await(5, TimeUnit.SECONDS);
	}
	
	@After
	public void tearDown() throws InterruptedException {
		ioLoop.addCallback(() -> ioLoop.stop());
		Thread.sleep(300);	// give the IOLoop thread some time to gracefully shutdown
	}

	private void closeQuietly(InputStream is, OutputStream os) {
		try {
			if (is != null) is.close();
			if (os != null) os.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void connectWriteAndReadCallbackTest() throws InterruptedException, IOException {
		AsyncResult<Boolean> ccb = new AsyncResult<Boolean>() {
			public void onFailure(Throwable caught) { }
			public void onSuccess(Boolean result) { onConnect(); }
		};
		socket.connect(HOST, port, ccb);
		
		latch.await(5, TimeUnit.SECONDS);
		
		assertEquals(0, latch.getCount());
		// TODO stop ioloop
	}
	
	private void onConnect() {
		latch.countDown();
		AsyncCallback wcb = new AsyncCallback() { @Override public void onCallback() { onWriteComplete(); }};
		socket.write("roger|\r\n", wcb);
	}
	
	private void onWriteComplete() {
		latch.countDown();
		AsyncResult<String> rcb = new AsyncResult<String>() { 
			@Override public void onFailure(Throwable caught) { assertTrue(false); }
			@Override public void onSuccess(String result) { onReadComplete(result); }
		};
		socket.readUntil("|", rcb);
	}
	
	private void onReadComplete(String result) {
		if ("ROGER".equals(result)) {
			latch.countDown();
		}
		assertEquals("ROGER", result);
	}
	
}
