package org.deftserver.example.kv;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.io.IOLoop;
import org.deftserver.web.Application;
import org.deftserver.web.AsyncResult;
import org.deftserver.web.Asynchronous;
import org.deftserver.web.HttpServer;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeyValueStoreExample {

	private final static Logger logger = LoggerFactory.getLogger(KeyValueStoreExample.class);
	private final static int PORT = 8080;
	
	private static class KeyValueStoreExampleRequestHandler extends RequestHandler {

		private final KeyValueStoreClient client = new KeyValueStoreClient(KeyValueStore.HOST, KeyValueStore.PORT);
		
		public KeyValueStoreExampleRequestHandler() {
			new KeyValueStore().start();
			client.connect();
		}
		
		@Override
		@Asynchronous
		public void get(HttpRequest request, final HttpResponse response) throws IOException {
			client.get("deft", new AsyncResult<String>() {
				@Override public void onFailure(Throwable caught) { /* ignore */}
				@Override public void onSuccess(String result) { 
					try {
						response.write(result).finish();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
		}
	}
	
	public static void main(String[] args) {
		Map<String, RequestHandler> handlers = new HashMap<String, RequestHandler>();
		handlers.put("/kv", new KeyValueStoreExampleRequestHandler());
		Application application = new Application(handlers);
		logger.debug("Starting up server on port: " + PORT);
		HttpServer server = new HttpServer(application);
		try {
			server.listen(PORT);
			IOLoop.INSTANCE.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
