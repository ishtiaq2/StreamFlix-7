package com.streamflix.core;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8981"));

        ResourceConfig config = new ResourceConfig();
        config.register(EventResource.class);
        config.register(AlarmResource.class);
        config.register(JacksonFeature.class);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");

        Server server = new Server(port);
        server.setHandler(context);

        System.out.println("core-engine (Jetty+Jersey) listening on :" + port);
        server.start();
        server.join();
    }
}
