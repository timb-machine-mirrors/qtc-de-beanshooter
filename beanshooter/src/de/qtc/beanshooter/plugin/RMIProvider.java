package de.qtc.beanshooter.plugin;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.ObjID;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.rmi.server.RemoteRef;
import java.util.Map;

import javax.management.MBeanServerConnection;
import javax.management.remote.rmi.RMIConnection;
import javax.management.remote.rmi.RMIConnector;
import javax.management.remote.rmi.RMIServer;

import de.qtc.beanshooter.cli.Option;
import de.qtc.beanshooter.exceptions.ExceptionHandler;
import de.qtc.beanshooter.io.Logger;
import de.qtc.beanshooter.networking.RMIEndpoint;
import de.qtc.beanshooter.networking.RMIRegistryEndpoint;
import de.qtc.beanshooter.utils.Utils;

/**
 * The RMIProvider provides an MBeanServerConnection by using regular Java RMI calls.
 *
 * @author Tobias Neitzel (@qtc_de)
 */
public class RMIProvider implements IMBeanServerProvider {

    private RMIEndpoint endpoint;
    private RMIRegistryEndpoint regEndpoint;

    /**
     * Obtain an MBeanServerConnection from the specified endpoint. How the endpoint is obtained depends
     * on other command line arguments.
     */
    @SuppressWarnings("resource")
    @Override
    public MBeanServerConnection getMBeanServerConnection(String host, int port, Map<String,Object> env)
    {
        endpoint = new RMIEndpoint(host, port);
        regEndpoint = new RMIRegistryEndpoint(endpoint);

        RMIServer rmiServer = null;
        RMIConnector rmiConnector = null;
        MBeanServerConnection connection = null;

        if( Option.TARGET_OBJID_CONNECTION.notNull() ) {

            ObjID objID = Utils.parseObjID(Option.TARGET_OBJID_CONNECTION.getValue());
            RMIConnection conn = getRMIConnectionByObjID(objID);

            rmiServer = new FakeRMIServer(conn);

        } else {
            rmiServer = getRMIServer();
        }

        rmiConnector = new RMIConnector(rmiServer, env);

        try {
            rmiConnector.connect();
            connection = rmiConnector.getMBeanServerConnection();

        } catch (IOException e) {
            ExceptionHandler.unexpectedException(e, "connecting to", "MBeanServer", true);
        }

        return connection;
    }

    /**
     * Returns an RMIConnection object. This is either obtained by performing a regular JMX login
     * or by using an ObjID value directly.
     *
     * @param env environment to use for regular JMX logins
     * @return RMIConnection to an remote MBeanServer
     */
    public RMIConnection getRMIConnection(Map<String,Object> env)
    {
        if( Option.TARGET_OBJID_CONNECTION.notNull() ) {

            ObjID objID = Utils.parseObjID(Option.TARGET_OBJID_CONNECTION.getValue());
            return getRMIConnectionByObjID(objID);
        }

        RMIServer server = getRMIServer();
        return getRMIConnectionByLogin(server, env);
    }

    /**
     * Obtains an RMIConnection object by performing a regular JMX login.
     *
     * @param server RMIServer to perform the login on
     * @param env environment to use for the login
     * @return RMIConnection to the remote MBeanServer
     */
    public RMIConnection getRMIConnectionByLogin(RMIServer server, Map<String,Object> env)
    {
        RMIConnection conn = null;

        try {
            conn = server.newClient(env);

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return conn;
    }

    /**
     * Obtains an RMIConnection object via ObjID.
     *
     * @param objID ObjID value of the remote object to connect to
     * @return RMIConnection to the remote MBeanServer
     */
    private RMIConnection getRMIConnectionByObjID(ObjID objID)
    {
        RemoteRef ref = endpoint.getRemoteRef(objID);
        RemoteObjectInvocationHandler handler = new RemoteObjectInvocationHandler(ref);

        return (RMIConnection)Proxy.newProxyInstance(RMIProvider.class.getClassLoader(), new Class[] { RMIConnection.class }, handler);
    }

    /**
     * Obtains an RMIServer object either via lookup on an RMI registry or via a directly specified
     * ObjID value.
     *
     * @return RMIServer object
     */
    private RMIServer getRMIServer()
    {
        if( Option.TARGET_OBJID_SERVER.notNull() ) {

            ObjID objID = Utils.parseObjID(Option.TARGET_OBJID_SERVER.getValue());
            return getRMIServerByObjID(objID);
        }

        return getRMIServerByLookup();
    }

    /**
     * Obtains an RMIServer object via a directly specified ObjID value.
     *
     * @param objID ObjID value of the targeted RMIServer
     * @return RMIServer object
     */
    private RMIServer getRMIServerByObjID(ObjID objID)
    {
        RemoteRef ref = endpoint.getRemoteRef(objID);
        RemoteObjectInvocationHandler handler = new RemoteObjectInvocationHandler(ref);

        return (RMIServer)Proxy.newProxyInstance(RMIProvider.class.getClassLoader(), new Class[] { RMIServer.class }, handler);
    }

    /**
     * Obtains an RMIServer object via an RMI registry lookup.
     *
     * @param boundName boundName to lookup on the RMI registry
     * @return RMIServer object
     */
    private RMIServer getRMIServerByLookup(String boundName)
    {
        RMIServer returnValue = null;

        try {
            returnValue = (RMIServer)regEndpoint.lookup(boundName);

        } catch (ClassNotFoundException e) {
            ExceptionHandler.lookupClassNotFoundException(e, e.getMessage());

        } catch( ClassCastException e) {
            Logger.printlnMixedYellow("Unable to cast remote object to", "RMIServer", "class.");
            Logger.printlnMixedBlue("You probbably specified a bound name that does not implement the", "RMIServer", "interface.");
            Utils.exit();
        }

        return returnValue;
    }

    /**
     * Obtains an RMIServer object via an RMI registry lookup.

     * @return RMIServer object
     */
    private RMIServer getRMIServerByLookup()
    {
        if( Option.TARGET_BOUND_NAME.notNull() )
            return getRMIServerByLookup(Option.TARGET_BOUND_NAME.getValue());

        String[] boundNames = regEndpoint.getBoundNames();
        Remote[] remoteObjects = Utils.filterJmxEndpoints(regEndpoint.lookup(boundNames));

        if( remoteObjects.length == 0 ) {
            Logger.printlnMixedYellow("The specified RMI registry", "does not", "contain any JMX objects.");
            Utils.exit();
        }

        return (RMIServer) remoteObjects[0];
    }

    /**
     * The FakeRMIServer class is used to wrap an RMIConnection into an MBeanServerConnection object.
     * Since there are no easy to use API functions to achieve this, we create a custom RMIServer object
     * that returns the RMIConnection object on login. When using the default JMX methods on this RMIServer
     * object, the returned RMIConnection gets automatically wrapped into an MBeanServerConnection.
     *
     * @author Tobias Neitzel (@qtc_de)
     */
    private class FakeRMIServer implements RMIServer {

        private final RMIConnection conn;

        /**
         * Initialize the FakeRMIServer with the RMIConnection object that should be returned on login.
         *
         * @param conn RMIConnection object to return on login
         */
        public FakeRMIServer(RMIConnection conn)
        {
            this.conn = conn;
        }

        /**
         * Not required but defined in RMIServer.
         */
        @Override
        public String getVersion() throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        /**
         * Just always return the stored RMIConnection object.
         */
        @Override
        public RMIConnection newClient(Object credentials) throws IOException
        {
            return conn;
        }
    }
}
