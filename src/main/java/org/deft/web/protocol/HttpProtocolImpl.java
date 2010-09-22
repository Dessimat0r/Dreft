package org.deft.web.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.deft.web.Application;
import org.deft.web.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpProtocolImpl implements HttpProtocol {
	
	private final static Logger logger = LoggerFactory.getLogger(HttpProtocolImpl.class);
	
	private static final int BUFFER_SIZE = 512;	//in bytes

	private final Application application;
	
	public HttpProtocolImpl(Application app) {
		application = app;
	}
	
	@Override
	public void handleAccept(SelectionKey key) throws IOException {
		//logger.debug("Received accept event");
		SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
		clientChannel.configureBlocking(false);
		clientChannel.register(key.selector(), SelectionKey.OP_READ, ByteBuffer.allocate(BUFFER_SIZE));
	}

	@Override
	public void handleRead(SelectionKey key) throws IOException {
		//logger.debug("Received read event");
		SocketChannel clientChannel = (SocketChannel) key.channel();
		ByteBuffer buffer = (ByteBuffer) key.attachment();
		long bytesRead = clientChannel.read(buffer);
		HttpRequest request = HttpRequest.of(buffer);
		RequestHandler rh = application.getHandler(request.getRequestedPath());
		if (rh != null) {
			HttpResponse response = new HttpResponse(clientChannel);
			rh.get(request, response);
		} else {
			// TODO RS 100921 send NOT FOUND (404)
		}
		clientChannel.close();	// remove this line ()
	}

	@Override
	public void handleWrite(SelectionKey key) throws IOException {
		logger.debug("Received write event");
		// TODO Auto-generated method stub

	}

}