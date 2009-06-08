package org.openhealthexchange.common.ws.server;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.util.Iterator;

import javax.xml.namespace.QName;

import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.ConfigurationContextFactory;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.SessionContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.engine.ListenerManager;
import org.apache.axis2.transport.TransportListener;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.server.HttpUtils;
import org.apache.axis2.transport.http.server.SessionManager;
import org.apache.axis2.util.OptionsParser;
import org.apache.log4j.Logger;


public class IheHTTPServer implements TransportListener {
	private static final Logger LOG = Logger.getLogger(SimpleHttpServer.class);

    /**
     * Embedded commons http core based server
     */
    SimpleHttpServer embedded = null;
    private String localAddress;
    int port = -1;

    public static int DEFAULT_PORT = 8080;

    private String hostAddress = null;

    protected ConfigurationContext configurationContext;
    protected IheHttpFactory httpFactory;
    private SessionManager sessionManager;

    public IheHTTPServer() {
    }

    /**
     * Create a IheHTTPServer using default IheHttpFactory settings
     */
    public IheHTTPServer(ConfigurationContext configurationContext, int port) throws AxisFault {
        this(new IheHttpFactory(configurationContext, port));
    }

    /**
     * Create a configured IheHTTPServer
     */
    public IheHTTPServer(IheHttpFactory httpFactory) throws AxisFault {
        this.httpFactory = httpFactory;
        this.configurationContext = httpFactory.getConfigurationContext();
        this.port = httpFactory.getPort();
        TransportInDescription httpDescription =
                new TransportInDescription(Constants.TRANSPORT_HTTP);
        httpDescription.setReceiver(this);
        httpFactory.getListenerManager().addListener(httpDescription, true);
        sessionManager = new SessionManager();
    }

    /**
     * init method in TransportListener
     *
     * @param axisConf
     * @param transprtIn
     * @throws AxisFault
     */
    public void init(ConfigurationContext axisConf, TransportInDescription transprtIn)
            throws AxisFault {
        try {
            this.configurationContext = axisConf;

            Parameter param = transprtIn.getParameter(PARAM_PORT);
            if (param != null) {
                this.port = Integer.parseInt((String) param.getValue());
            }

            if (httpFactory == null) {
                httpFactory = new IheHttpFactory(configurationContext, port);
            }

            param = transprtIn.getParameter(HOST_ADDRESS);
            if (param != null) {
                hostAddress = ((String) param.getValue()).trim();
            } else {
                hostAddress = httpFactory.getHostAddress();
            }
        } catch (Exception e1) {
            throw AxisFault.makeFault(e1);
        }
    }

    /**
     * Method main
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        OptionsParser optionsParser = new OptionsParser(args);

        args = optionsParser.getRemainingArgs();
        // first check if we should print usage
        if ((optionsParser.isFlagSet('?') > 0) || (optionsParser.isFlagSet('h') > 0) ||
            args == null || args.length == 0 || args.length > 3) {
            printUsage();
        }
        String paramPort = optionsParser.isValueSet('p');
        if (paramPort != null) {
            port = Integer.parseInt(paramPort);
        }

        boolean startAllTransports = "all".equals(optionsParser.isValueSet('t'));
        String repository = optionsParser.isValueSet('r');
        if (repository == null) {
            args = optionsParser.getRemainingArgs();
            if (args != null && args[0] != null && !args[0].equals("")) {
                repository = args[0];
            } else {
                printUsage();
            }
        }

        System.out.println("[IheHTTPServer] Starting");
        System.out.println("[IheHTTPServer] Using the Axis2 Repository "
                           + new File(repository).getAbsolutePath());
        System.out.println("[IheHTTPServer] Listening on port " + port);
        try {
            ConfigurationContext configctx = ConfigurationContextFactory
                    .createConfigurationContextFromFileSystem(repository, null);
            IheHTTPServer receiver = new IheHTTPServer(configctx, port);
            Runtime.getRuntime().addShutdownHook(new ShutdownThread(receiver));
            receiver.start();
            ListenerManager listenerManager = configctx .getListenerManager();
            TransportInDescription trsIn = new TransportInDescription(Constants.TRANSPORT_HTTP);
            trsIn.setReceiver(receiver);
            if (listenerManager == null) {
                listenerManager = new ListenerManager();
                listenerManager.init(configctx);
            }
            listenerManager.addListener(trsIn, true);

            // should all transports be started? specified as "-t all"
            if (startAllTransports) {
                Iterator iter = configctx.getAxisConfiguration().
                        getTransportsIn().keySet().iterator();
                while (iter.hasNext()) {
                    QName trp = (QName) iter.next();
                    if (!new QName(Constants.TRANSPORT_HTTP).equals(trp)) {
                        trsIn = (TransportInDescription)
                                configctx.getAxisConfiguration().getTransportsIn().get(trp);
                        listenerManager.addListener(trsIn, false);
                    }
                }
            }

            System.out.println("[IheHTTPServer] Started");
        } catch (Throwable t) {
            LOG.fatal("Error starting IheHTTPServer", t);
            System.out.println("[IheHTTPServer] Shutting down");
        }
    }

    public static void printUsage() {
        System.out.println("Usage: IheHTTPServer [options] -r <repository>");
        System.out.println(" Opts: -? this message");
        System.out.println();
        System.out.println("       -p port :to listen on (default is 8080)");
        System.out.println(
                "       -t all  :to start all transports defined in the axis2 configuration");
        System.exit(1);
    }


    /**
     * Start this server as a NON-daemon.
     */
    public void start() throws AxisFault {
        try {
            embedded = new SimpleHttpServer(httpFactory, port);
            embedded.init();
            embedded.start();
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
            throw AxisFault.makeFault(e);
        }
    }

    /**
     * Stop this server. Can be called safely if the system is already stopped,
     * or if it was never started.
     * This will interrupt any pending accept().
     */
    public void stop() {
        System.out.println("[IheHTTPServer] Stop called");
        if (embedded != null) {
            try {
                embedded.destroy();
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
            }
        }
    }

    /**
     * replyToEPR
     * If the user has given host address paramter then it gets the high priority and
     * ERP will be creatd using that
     * N:B - hostAddress should be a complete url (http://www.myApp.com/ws)
     *
     * @param serviceName
     * @param ip
     * @return an EndpointReference
     * @see org.apache.axis2.transport.TransportListener#getEPRForService(String,String)
     */
    public EndpointReference[] getEPRsForService(String serviceName, String ip) throws AxisFault {
        //if host address is present
        if (hostAddress != null) {
            if (embedded != null) {
                String endpointRefernce = hostAddress ;
                if(configurationContext.getServiceContextPath().startsWith("/")){
                    endpointRefernce =  endpointRefernce +
                            configurationContext.getServiceContextPath() + "/" + serviceName;
                } else {
                    endpointRefernce = endpointRefernce + '/' +
                            configurationContext.getServiceContextPath() + "/" + serviceName;
                }
                return new EndpointReference[]{new EndpointReference(endpointRefernce + "/")};
            } else {
                throw new AxisFault("Unable to generate EPR for the transport : http");
            }
        }
        //if the host address is not present
        String ipAddress;
        if (ip != null) {
            ipAddress = ip;
        } else {
            try {
                if(localAddress==null){
                    localAddress = HttpUtils.getIpAddress(configurationContext.getAxisConfiguration());
                }
                if (localAddress == null) {
                   ipAddress = "127.0.0.1";
                 } else {
                    ipAddress = localAddress;
                 }
            } catch (SocketException e) {
                throw AxisFault.makeFault(e);
            }
        }
        if (embedded != null) {
            String endpointRefernce = "http://" + ipAddress + ":" + embedded.getPort() ;
            if(configurationContext.getServiceContextPath().startsWith("/")){
                endpointRefernce =  endpointRefernce +
                        configurationContext.getServiceContextPath() + "/" + serviceName;
            } else {
                endpointRefernce = endpointRefernce + '/' +
                        configurationContext.getServiceContextPath() + "/" + serviceName;
            }


            return new EndpointReference[]{new EndpointReference(endpointRefernce + "/")};
        } else {
            throw new AxisFault("Unable to generate EPR for the transport : http");
        }
    }

    /**
     * Getter for httpFactory
     */
    public IheHttpFactory getTestHttpFactory() {
        return httpFactory;
    }

    /**
     * Method getConfigurationContext
     *
     * @return the system context
     */
    public ConfigurationContext getConfigurationContext() {
        return configurationContext;
    }

    /**
     * replyToEPR
     * If the user has given host address paramter then it gets the high priority and
     * ERP will be creatd using that
     * N:B - hostAddress should be a complte url (http://www.myApp.com/ws)
     *
     * @param serviceName
     * @param ip
     * @return an EndpointReference
     * @see org.apache.axis2.transport.TransportListener#getEPRForService(String,String)
     */
    public EndpointReference getEPRForService(String serviceName, String ip) throws AxisFault {
        return getEPRsForService(serviceName, ip)[0];
    }

    /**
     * Checks if this HTTP server instance is running.
     *
     * @return true/false
     */
    public boolean isRunning() {
        if (embedded == null) {
            return false;
        }

        return embedded.isRunning();
    }

    static class ShutdownThread extends Thread {
        private IheHTTPServer server = null;

        public ShutdownThread(IheHTTPServer server) {
            super();
            this.server = server;
        }

        public void run() {
            System.out.println("[IheHTTPServer] Shutting down");
            server.stop();
            System.out.println("[IheHTTPServer] Shutdown complete");
        }
    }


    public SessionContext getSessionContext(MessageContext messageContext) {
        String sessionKey = (String) messageContext.getProperty(HTTPConstants.COOKIE_STRING);
        return this.sessionManager.getSessionContext(sessionKey);
    }

    public void destroy() {
        this.configurationContext = null;
    }

}