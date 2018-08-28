/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.intentnetcontrol;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onosproject.app.ApplicationService;
import org.onosproject.component.ComponentService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.intentsync.IntentSynchronizationService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.intent.IntentService;
import org.onosproject.routing.bgp.BgpInfoService;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Component for the ONOS intent-based networking control application.
 */

@Component(immediate = true)

public class IntentNetworkingControl {

    public static final String INTENT_NETWORKING_CONTROL_APP = "org.onosproject.intentnetcontrol";

    private static final Logger log = getLogger(IntentNetworkingControl.class);

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ApplicationService applicationService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentService componentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentSynchronizationService intentSyncService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigService configService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected BgpInfoService bgpInfoService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry registry;

    private ApplicationId appId;

    Class<IntentNetworkingControl> configClass = IntentNetworkingControl.class;
    public static final String CONFIG_KEY = "members";

    private ConfigFactory configFactory =
            new ConfigFactory(SubjectFactories.APP_SUBJECT_FACTORY,
                              configClass, CONFIG_KEY) {
                @Override
                public IntentNetworkingControlConfig createConfig() {
                    return new IntentNetworkingControlConfig();
                }
            };

    @Activate
    protected void activate() {
        componentService.activate(appId, IntentNetworkingControl.class.getName());
        appId = coreService.registerApplication(INTENT_NETWORKING_CONTROL_APP);
        registry.registerConfigFactory(configFactory);

        IntentNetworkingControlDaemon intentNetworkingControlDaemon =
                new IntentNetworkingControlDaemon(appId,
                                                  intentService,
                                                  intentSyncService,
                                                  configService,
                                                  bgpInfoService);

        intentNetworkingControlDaemon.daemonize();
        log.info("Intent Networking Control Application is Started");
    }

    @Deactivate
    protected  void deactivate() {
        registry.unregisterConfigFactory(configFactory);
        log.info("Intent Networking Control Application is Stopped");
    }

}
