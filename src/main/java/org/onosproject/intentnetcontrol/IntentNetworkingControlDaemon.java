package org.onosproject.intentnetcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onosproject.core.ApplicationId;
import org.onosproject.intentsync.IntentSynchronizationService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.routing.bgp.BgpInfoService;
import org.onosproject.routing.bgp.BgpRouteEntry;
import org.onosproject.routing.bgp.BgpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Execute the Intent Networking Control regularly.
 */
public class IntentNetworkingControlDaemon {

    private static final String INTENT_API = "/onos/v1/intents/";
    private static final String SDN_IP_APP = "org.onosproject.sdnip";
    private static final int PRIORITY = 300;
    Class<IntentNetworkingControlConfig> configClass = IntentNetworkingControlConfig.class;
    private static String sinkPort = "";
    private static final String LOCAL_ASN = "65011";

    private static final Logger log = LoggerFactory.getLogger(
            IntentNetworkingControlDaemon.class);

    private final IntentService intentService;
    private final IntentSynchronizationService intentSynchronizer;
    private final NetworkConfigService configService;
    private final BgpInfoService bgpInfoService;

    private final ApplicationId appId;

    private static final String VISIBILITY_SERVER = "210.125.84.140";
    private static final String FLOW_API = "/api/onosbuild2017/";
    private static final Integer THRESHOLD = 100000;


    /**
     * Creates a Inter Networking Control Daemon.
     *
     * @param appId              the application ID
     * @param intentService      the intent service
     * @param intentSynchronizer the intent synchronizer
     * @param configService      the network config service
     * @param bgpInfoService     the BGP information service
     */
    public IntentNetworkingControlDaemon (ApplicationId appId,
                                   IntentService intentService,
                                   IntentSynchronizationService intentSynchronizer,
                                   NetworkConfigService configService,
                                   BgpInfoService bgpInfoService) {
        this.appId = appId;
        this.intentService = intentService;
        this.intentSynchronizer = intentSynchronizer;
        this.configService = configService;
        this.bgpInfoService = bgpInfoService;
    }

    /**
     * Activate the networking control as daemon.
     */

    public void daemonize() {

        while (true) {
            try {
                check();
                Thread.sleep(10000);

            } catch(InterruptedException e) {}
        }
    }

    /**
     * Activate the networking control.
     */

    public void check() {

        String listFlows = "";
        Integer packetCount = 0;
        String sourceAddress = "";

        {
            try {

                listFlows = "{\"FlowArray\" : " + checkFlow() + "}";

            }catch (IOException ie){
                ie.printStackTrace();
            }
        }

        ObjectMapper objectMapper = new ObjectMapper();

        try {

            JsonNode flowArray = objectMapper.readTree(listFlows).get("FlowArray");

            for (JsonNode flow : flowArray) {
                log.info (flow.toString());
                sourceAddress = flow.get("source_address").asText();
                packetCount = flow.get("number_of_packet").asInt();

                //print (sourceAddress + ":" + packetCount.toString());

                if (packetCount >= THRESHOLD) {
                    String[] IP = sourceAddress.split("\\.");
                    String subnetAddress = IP[0] + "." + IP[1] + "." + IP[2] + ".0/24";
                    log.info ("Activate rule for route : %s", subnetAddress);
                    activate(subnetAddress);
                }
            }

        } catch (IOException ie) {
            ie.printStackTrace();
        }
    }

    private String checkFlow() throws IOException {

        String flowAPIURL = "http://" + VISIBILITY_SERVER + ":8000" + FLOW_API;
        URL url = null;
        String flows = "";

        try {
            url = new URL(flowAPIURL);
        }
        catch (MalformedURLException e) {
            e.printStackTrace();
        }

        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        int responseCode = urlConnection.getResponseCode();
        if (responseCode == 200) {
            InputStream is = urlConnection.getInputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(is));
            flows = in.readLine();
            log.info ("Flows Exist");
        }
        else {
            log.info("Flows Not Exist");
        }
        return flows;
    }

    /**
     * Activate the networking control.
     */

    public void activate(String route) {

        String asn = "";

        if (routeToAsn(route) == null) {
            log.info("No originating AS Number for this prefix %s", route);
        }

        if (routeToAsn(route).equals(checkLocalAsn(routeToAsn(route)))) {

            log.info("This prefix %s is originating from Local AS Number (AS %s)", route, LOCAL_ASN);

            if (checkLocalIntent(route) == null) {
                log.info("No local intent is installed for this prefix %s", route);
            } else {
                log.info("Local intent is installed for this prefix %s", route);
                modifyIntent(route);
            }

        } else {

            log.info("This prefix %s is originating from Remote AS Number %s", route, routeToAsn(route));

            String matchRemoteIntent = checkRemoteIntent(routeToAsn(route), route);
            log.info(matchRemoteIntent);

            if (matchRemoteIntent == null) {
                log.info("No remote intent is installed for this prefix %s", route);
            } else {
                log.info("remote intent is installed for this prefix %s", route);
                modifyRemoteIntent(routeToAsn(route), matchRemoteIntent);
            }

        }

    }

    /**
     * Gets specific installed intents for specific route prefix.
     *
     * @param route route prefix to be checked
     *
     * @return the multi-point-to-single-point intent of SDN-IP.
     */

    private Intent checkLocalIntent(String route) {


        Intent intentRoute = null;
        for (Intent intent : intentService.getIntents()) {
            /**
             * Only get intent MultiPointToSinglePoint type
             */

            if (intent instanceof MultiPointToSinglePointIntent) {
                MultiPointToSinglePointIntent pi = (MultiPointToSinglePointIntent) intent;
                String key = String.format("%s", pi.key());
                if (key.equals(route)) {
                    //print("%s", pi);
                    intentRoute = pi;
                    break;
                }
            }
        }
        return intentRoute;
    }

    /**
     * Gets specific last/originating AS Number for specific route prefix.
     *
     * @param route route prefix to be checked
     *
     * @return the last/origination AS Number.
     */

    private String routeToAsn(String route) {

        String lastAsn = "";
        BgpSession foundBgpSession = null;
        for (BgpSession bgpSession : bgpInfoService.getBgpSessions()) {
            foundBgpSession = bgpSession;
        }
        /**
         * Get the ASPath for specific BGP Peering
         */

        Collection<BgpRouteEntry> routes4 = foundBgpSession.getBgpRibIn4();
        for (BgpRouteEntry route4 : routes4) {
            String route4string = String.format("%s", route4.prefix());
            if ((route4string.equals(route))) {
                //print("%s", lastAsNumber(route4.getAsPath()));
                lastAsn = lastAsNumber(route4.getAsPath());
            }
        }
        return lastAsn;
    }

    /**
     * Check and print installed intents for specific route prefix
     * in remote controller participating in intent-based networking control.
     *
     * @param route route prefix to be checked
     * @param asn origination AS Number for the route
     *
     * @return installed multi-point-to-single-point intent in remote controller
     */

    private String checkRemoteIntent(String asn, String route) {

        Class<IntentNetworkingControlConfig> configClass = IntentNetworkingControlConfig.class;
        IntentNetworkingControlConfig memberConfig =
                configService.getConfig(appId, configClass);
        Set<IntentNetworkingControlConfig.ControllerConfig> memberControllers;
        String installedIntent = "";

        if (memberConfig == null || memberConfig.controllers().isEmpty()) {
            log.info("no configuration");
        }

        /**
         * Iterate all members configuration and stop for matched AS Number
         */

        memberControllers = memberConfig.controllers();

        /*memberConfig.controllers().forEach(controllerConfig -> {
            print("%s", controllerConfig.asn());
            print("%s", controllerConfig.ip());
            print("%s", controllerConfig.username());
            print("%s", controllerConfig.password());
            print("%s", controllerConfig.sinkPort());
            if (asn.equals(controllerConfig.asn())) {
                originatingMember = controllerConfig;
            }
        });*/

        for (IntentNetworkingControlConfig.ControllerConfig memberController : memberControllers) {
            if (memberController.asn().equals(asn)) {
                try {
                    installedIntent = checkIntentApi(memberController.ip(),
                                                     memberController.username(),
                                                     memberController.password(),
                                                     route);
                } catch (IOException ie) {
                    ie.printStackTrace();
                }
            }
        }
        return installedIntent;
    }

    /**
     * Check local or remote controller based on the AS number.
     *
     * @param asn origination AS Number for the route
     *
     * @return local or remote type of controller
     */

    private String checkLocalAsn(String asn) {

        Class<IntentNetworkingControlConfig> configClass = IntentNetworkingControlConfig.class;
        IntentNetworkingControlConfig memberConfig =
                configService.getConfig(appId, configClass);
        Set<IntentNetworkingControlConfig.ControllerConfig> memberControllers;
        String localAsn = "";

        if (memberConfig == null || memberConfig.controllers().isEmpty()) {
            log.info("no configuration");
        }

        /**
         * Iterate all members configuration and stop for matched AS Number
         */

        memberControllers = memberConfig.controllers();

        for (IntentNetworkingControlConfig.ControllerConfig memberController : memberControllers) {
            if (memberController.asn().equals(asn)) {
                if (memberController.controllerType() == "local") {
                    localAsn = asn;
                }
            }
        }
        return localAsn;
    }

    /**
     * Gets specific last/originating AS Number from AS Path.
     *
     * @param asPath AS Path to be checked from specific route
     *
     * @return the last/origination AS Number.
     */

    private String lastAsNumber(BgpRouteEntry.AsPath asPath) {

        String lastSegment = "";

        ArrayList<BgpRouteEntry.PathSegment> pathSegments =
                asPath.getPathSegments();

        /**
         * Only get last segment of the AS Path
         */

        for (BgpRouteEntry.PathSegment pathSegment : pathSegments) {
            for (Long asn : pathSegment.getSegmentAsNumbers()) {
                if (pathSegment == pathSegments.get(pathSegments.size() - 1)) {
                    lastSegment = asn.toString();
                }

            }
        }
        return lastSegment;
    }

    /**
     * Check and print the status of installed intents through intent REST API
     * for specific route prefix in remote controller participating in intent-based networking control
     * with given configuration from SDX coordination application config.
     *
     * @param onosIp Remote Controller's IP address
     * @param user Remote Controller's username
     * @param password Remote Controller's password
     * @param route route prefix to be checked
     */

    private String checkIntentApi(String onosIp, String user, String password, String route) throws IOException {

        String intentUrl = "http://" + onosIp + ":8181" + INTENT_API + SDN_IP_APP + "/" + route.replaceAll("/", "%2F");
        URL url = null;
        String intent = "";

        try {
            url = new URL(intentUrl);
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, password.toCharArray());
                }
            });
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        /**
         * Make REST API Call to remote controller
         */

        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        int responseCode = urlConnection.getResponseCode();
        if (responseCode == 200) {
            InputStream is = urlConnection.getInputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(is));
            intent = in.readLine();
            //print (intent);
            log.info("Intent Exist");
        } else {
            //print (intent);
            log.info("Intent Not Exist");
        }

        return intent;
    }

    /**
     * Modify installed intents for specific route prefix.
     *
     * @param route intent for specific route prefix to be checked
     */

    private void modifyIntent(String route) {

        IntentNetworkingControlConfig memberConfig =
                configService.getConfig(appId, configClass);
        Set<ConnectPoint> filteredIngressPoint = new HashSet<>();


        if (memberConfig == null || memberConfig.controllers().isEmpty()) {
            log.info("no configuration");
            return;
        }

        for (Intent intent : intentService.getIntents()) {
            if (intent instanceof MultiPointToSinglePointIntent) {
                MultiPointToSinglePointIntent pi = (MultiPointToSinglePointIntent) intent;
                String key = String.format("%s", pi.key());
                if (key.equals(route)) {
                    //    print("%s", pi.toString());
                    //    print("%s", pi.id().toString());
                    //    print("%s", pi.key().toString());
                    //    print("%s", pi.priority());
                    //print("%s", pi.resources().toString());
                    //    print("%s", pi.selector().toString());
                    //    print("%s", pi.treatment().toString());

                    /*
                    for (ConnectPoint ingressPoint : pi.ingressPoints()) {
                        if (ingressPoint.toString().equals(pi.egressPoint().toString())) {
                            //print("same interface");
                        } else {
                            //print("%s", ingressPoint.toString());
                            filteredIngressPoint.add(ingressPoint);
                        }
                    }
                    */

                    //print("%s", pi.egressPoint().toString());
                    //print("%s", pi.constraints());

                    sinkPort = findSinkPort(route);
                    FilteredConnectPoint sinkPoint =
                            new FilteredConnectPoint(ConnectPoint.deviceConnectPoint(sinkPort));
                    //print("%s", SINKPORT);
                    //ConnectPoint sinkPortCP = ConnectPoint.deviceConnectPoint(SINKPORT);
                    //print("%s", sinkPortCP.toString());

                    /*Intent modifiedIntent = MultiPointToSinglePointIntent.builder()
                            .appId(intentNetworkingControlAppId)
                            .key(Key.of(pi.key().toString(), intentNetworkingControlAppId))
                            .selector(pi.selector())
                            .treatment(pi.treatment())
                            .ingressPoints(filteredIngressPoint)
                            .egressPoint(ConnectPoint.deviceConnectPoint(sinkPort))
                            .constraints(pi.constraints())
                            .priority(PRIORITY)
                            .build();
                            */

                    Intent modifiedIntent = PointToPointIntent.builder()
                            .appId(appId)
                            .key(Key.of(pi.key().toString(), appId))
                            .filteredIngressPoint(pi.filteredEgressPoint())
                            .filteredEgressPoint(sinkPoint)
                            .priority(PRIORITY)
                            .build();

                    intentService.submit(modifiedIntent);
                    log.info("Modified Multipoint to single point intent submitted:\n%s", modifiedIntent.toString());
                }
            }
        }
    }

    /**
     * Add point-to-point intent to override installed multi-point-to-single-point intent
     * for specific route prefix in local controller.
     *
     * @param asn of originating route prefix
     * @param matchIntent installed intent for specific route prefix to be modified
     */

    private void modifyRemoteIntent(String asn, String matchIntent) {

        IntentNetworkingControlConfig memberConfig =
                configService.getConfig(appId, configClass);
        //print("%s", memberConfig.toString());
        if (memberConfig == null || memberConfig.controllers().isEmpty()) {
            log.info("no configuration");
            return;
        }

        log.info("%s", matchIntent);

        memberConfig.controllers().forEach(controllerConfig -> {
            log.info("%s", controllerConfig.asn());
            log.info("%s", controllerConfig.ip());
            log.info("%s", controllerConfig.username());
            log.info("%s", controllerConfig.password());
            log.info("%s", controllerConfig.sinkPort());
            log.info("%s", matchIntent);

            if (asn.equals(controllerConfig.asn())) {
                try {
                    log.info(matchIntent);
                    modifyIntentApi(controllerConfig.ip(),
                                    controllerConfig.username(),
                                    controllerConfig.password(),
                                    matchIntent,
                                    controllerConfig.sinkPort());
                } catch (IOException ie) {
                    ie.printStackTrace();
                }
                return;
            }
        });
    }

    /**
     * Add point-to-point intent to override installed multi-point-to-single-point intent
     * for specific route prefix in remote controller through REST API.
     *
     * @param onosIp Remote Controller's IP address
     * @param user Remote Controller's username
     * @param password Remote Controller's password
     * @param matchIntent Intent need to be modified/override
     * @param sinkPort port for redirecting the traffic
     */

    private void modifyIntentApi(String onosIp,
                                 String user,
                                 String password,
                                 String matchIntent,
                                 String sinkPort) throws IOException {

        log.info(matchIntent);
        URL url = null;
        //String intentUrl = "http://172.30.91.112:8181/onos/v1/intents";
        String intentUrl = "http://" + onosIp + ":8181" + INTENT_API;

        // Parse JSON String to get MP2SP intent egress Port
        ObjectMapper mapper = new ObjectMapper();
        JsonNode matchIntentJson = mapper.readValue(matchIntent, JsonNode.class);
        JsonNode matchIntentEgressPoint = matchIntentJson.get("egressPoint");
        ObjectNode modifyIngressPort = mapper.createObjectNode();
        modifyIngressPort.put("port", matchIntentEgressPoint.get("port"));
        modifyIngressPort.put("device", matchIntentEgressPoint.get("device"));

        // Create egress Port for Policy Config
        String[] sinkPortComponent = sinkPort.split("/");

        // Create JSON for P2P intent
        JsonNode intentJson = mapper.createObjectNode();
        ((ObjectNode) intentJson).put("type", "PointToPointIntent");
        ((ObjectNode) intentJson).put("appId", IntentNetworkingControl.INTENT_NETWORKING_CONTROL_APP);
        ((ObjectNode) intentJson).put("priority", String.valueOf(PRIORITY));
        //JsonNode ingressPoint = mapper.createObjectNode();
        //((ObjectNode) ingressPoint).put("port", "4");
        //((ObjectNode) ingressPoint).put("device", "of:0000000000000002");
        //((ObjectNode) intentJson).set("ingressPoint", ingressPoint);
        ((ObjectNode) intentJson).set("ingressPoint", modifyIngressPort);
        JsonNode egressPoint = mapper.createObjectNode();
        ((ObjectNode) egressPoint).put("port", sinkPortComponent[1]);
        ((ObjectNode) egressPoint).put("device", sinkPortComponent[0]);
        ((ObjectNode) intentJson).set("egressPoint", egressPoint);

        System.out.println(intentJson);

        try {
            url = new URL(intentUrl);
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, password.toCharArray());
                }
            });

            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type", "application/json");
            OutputStream os = urlConnection.getOutputStream();
            os.write(intentJson.toString().getBytes());
            os.flush();

            if (urlConnection.getResponseCode() != HttpURLConnection.HTTP_CREATED) {
                throw new RuntimeException("Failed : HTTP error code : "
                                                   + urlConnection.getResponseCode());
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(
                    (urlConnection.getInputStream())));

            String output;
            System.out.println("Output from Server .... \n");
            while ((output = br.readLine()) != null) {
                System.out.println(output);
            }
            urlConnection.disconnect();

        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

    }

    /**
     * Find the sink port configuration from the member controller
     * based on the route prefix.
     *
     * @param route route prefix to be checked
     *
     * @return the sink port location
     */

    private String findSinkPort(String route) {
        Class<IntentNetworkingControlConfig> configClass = IntentNetworkingControlConfig.class;
        IntentNetworkingControlConfig memberConfig =
                configService.getConfig(appId, configClass);
        if (memberConfig == null || memberConfig.controllers().isEmpty()) {
            log.info("no configuration");
            return null;
        }

        /**
         * Find the AS Number from the prefix
         */

        String asn = routeToAsn(route);

        /**
         * Iterate all members configuration and stop for matched AS Number
         */

        memberConfig.controllers().forEach(controllerConfig -> {
            //print("%s", controllerConfig.sinkPort());
            if (asn.equals(controllerConfig.asn())) {
                sinkPort = controllerConfig.sinkPort();
            }
        });
        return sinkPort;
    }

}