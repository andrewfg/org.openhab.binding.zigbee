/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.zigbee.internal.converter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.eclipse.jdt.annotation.NonNull;
import org.openhab.binding.zigbee.ZigBeeBindingConstants;
import org.openhab.binding.zigbee.converter.ZigBeeBaseChannelConverter;
import org.openhab.binding.zigbee.handler.ZigBeeThingHandler;
import org.openhab.binding.zigbee.internal.converter.config.ZclReportingConfig;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zsmartsystems.zigbee.CommandResult;
import com.zsmartsystems.zigbee.ZigBeeEndpoint;
import com.zsmartsystems.zigbee.zcl.ZclAttribute;
import com.zsmartsystems.zigbee.zcl.ZclAttributeListener;
import com.zsmartsystems.zigbee.zcl.clusters.ZclRelativeHumidityMeasurementCluster;
import com.zsmartsystems.zigbee.zcl.protocol.ZclClusterType;

/**
 * Converter for the relative humidity channel
 *
 * @author Chris Jackson - Initial Contribution
 *
 */
public class ZigBeeConverterRelativeHumidity extends ZigBeeBaseChannelConverter implements ZclAttributeListener {
    private Logger logger = LoggerFactory.getLogger(ZigBeeConverterRelativeHumidity.class);

    private ZclRelativeHumidityMeasurementCluster cluster;
    private ZclAttribute attribute;

    private static BigDecimal CHANGE_DEFAULT = new BigDecimal(1);
    private static BigDecimal CHANGE_MIN = new BigDecimal(1);
    private static BigDecimal CHANGE_MAX = new BigDecimal(100);

    private ZclReportingConfig configReporting;

    @Override
    public Set<Integer> getImplementedClientClusters() {
        return Collections.singleton(ZclRelativeHumidityMeasurementCluster.CLUSTER_ID);
    }

    @Override
    public Set<Integer> getImplementedServerClusters() {
        return Collections.emptySet();
    }

    @Override
    public boolean initializeDevice() {
        ZclRelativeHumidityMeasurementCluster serverCluster = (ZclRelativeHumidityMeasurementCluster) endpoint
                .getInputCluster(ZclRelativeHumidityMeasurementCluster.CLUSTER_ID);
        if (serverCluster == null) {
            logger.error("{}: Error opening device relative humidity measurement cluster", endpoint.getIeeeAddress());
            return false;
        }

        ZclReportingConfig reporting = new ZclReportingConfig(channel);

        try {
            CommandResult bindResponse = bind(serverCluster).get();
            if (bindResponse.isSuccess()) {
                // Configure reporting
                ZclAttribute attribute = serverCluster
                        .getAttribute(ZclRelativeHumidityMeasurementCluster.ATTR_MEASUREDVALUE);
                CommandResult reportingResponse = attribute.setReporting(reporting.getReportingTimeMin(),
                        reporting.getReportingTimeMax(), reporting.getReportingChange()).get();
                handleReportingResponse(reportingResponse, POLLING_PERIOD_DEFAULT, REPORTING_PERIOD_DEFAULT_MAX);
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.error("{}: Exception setting reporting ", endpoint.getIeeeAddress(), e);
            pollingPeriod = POLLING_PERIOD_HIGH;
            return false;
        }
        return true;
    }

    @Override
    public boolean initializeConverter(ZigBeeThingHandler thing) {
        super.initializeConverter(thing);
        cluster = (ZclRelativeHumidityMeasurementCluster) endpoint
                .getInputCluster(ZclRelativeHumidityMeasurementCluster.CLUSTER_ID);
        if (cluster == null) {
            logger.error("{}: Error opening device relative humidity measurement cluster", endpoint.getIeeeAddress());
            return false;
        }

        attribute = cluster.getAttribute(ZclRelativeHumidityMeasurementCluster.ATTR_MEASUREDVALUE);
        if (attribute == null) {
            logger.error("{}: Error opening device measured value attribute", endpoint.getIeeeAddress());
            return false;
        }

        // Add a listener, then request the status
        cluster.addAttributeListener(this);

        // Create a configuration handler and get the available options
        configReporting = new ZclReportingConfig(channel);
        configReporting.setAnalogue(CHANGE_DEFAULT, CHANGE_MIN, CHANGE_MAX);
        configOptions = new ArrayList<>();
        configOptions.addAll(configReporting.getConfiguration());

        return true;
    }

    @Override
    public void disposeConverter() {
        cluster.removeAttributeListener(this);
    }

    @Override
    public void handleRefresh() {
        attribute.readValue(0);
    }

    @Override
    public void updateConfiguration(@NonNull Configuration currentConfiguration,
            Map<String, Object> updatedParameters) {
        if (configReporting.updateConfiguration(currentConfiguration, updatedParameters)) {
            try {
                ZclAttribute attribute = cluster.getAttribute(ZclRelativeHumidityMeasurementCluster.ATTR_MEASUREDVALUE);
                CommandResult reportingResponse;
                reportingResponse = attribute.setReporting(configReporting.getReportingTimeMin(),
                        configReporting.getReportingTimeMax(), configReporting.getReportingChange()).get();
                handleReportingResponse(reportingResponse, configReporting.getPollingPeriod(),
                        configReporting.getReportingTimeMax());
            } catch (InterruptedException | ExecutionException e) {
                logger.debug("{}: Relative humidity measurement exception setting reporting", endpoint.getIeeeAddress(),
                        e);
            }
        }
    }

    @Override
    public Channel getChannel(ThingUID thingUID, ZigBeeEndpoint endpoint) {
        if (endpoint.getInputCluster(ZclRelativeHumidityMeasurementCluster.CLUSTER_ID) == null) {
            logger.trace("{}: Relative humidity cluster not found", endpoint.getIeeeAddress());
            return null;
        }

        return ChannelBuilder
                .create(createChannelUID(thingUID, endpoint, ZigBeeBindingConstants.CHANNEL_NAME_HUMIDITY_VALUE),
                        ZigBeeBindingConstants.ITEM_TYPE_NUMBER)
                .withType(ZigBeeBindingConstants.CHANNEL_HUMIDITY_VALUE)
                .withLabel(ZigBeeBindingConstants.CHANNEL_LABEL_HUMIDITY_VALUE)
                .withProperties(createProperties(endpoint)).build();
    }

    @Override
    public void attributeUpdated(ZclAttribute attribute, Object val) {
        if (attribute.getClusterType() == ZclClusterType.RELATIVE_HUMIDITY_MEASUREMENT
                && attribute.getId() == ZclRelativeHumidityMeasurementCluster.ATTR_MEASUREDVALUE) {
            logger.debug("{}: ZigBee attribute reports {}", endpoint.getIeeeAddress(), attribute);
            Integer value = (Integer) val;
            updateChannelState(new DecimalType(BigDecimal.valueOf(value, 2)));
        }
    }
}
