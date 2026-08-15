import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws IOException {

        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(8080),
                        0
                );

        ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();

        server.setExecutor(executor);

        Handler.register(server);

        server.start();

        System.out.println("Server started on port 8080");
        System.out.println("Open: http://localhost:8080");
    }
}