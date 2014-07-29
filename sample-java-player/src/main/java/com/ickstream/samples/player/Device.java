/*
 * Copyright (C) 2013-2014 ickStream GmbH
 * All rights reserved
 */

package com.ickstream.samples.player;

import com.ickstream.protocol.service.core.DeviceResponse;
import com.ickstream.protocol.service.player.PlayerConfigurationResponse;
import com.ickstream.protocol.service.player.PlayerService;

/**
 * Representation of a device on local network
 */
public class Device {
    /**
     * Identity of the device
     */
    String id;

    /**
     * Name of the device, this might be null if the device doesn't have a name
     */
    String name;

    /**
     * More information about the device in case it's registered in Cloud Core service
     */
    DeviceResponse registeredInformation;

    /**
     * Player service client
     */
    PlayerService playerService;

    /**
     * More information about the player configuration
     */
    PlayerConfigurationResponse playerConfiguration;

    public Device(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public Device(String id, DeviceResponse registeredInformation) {
        this.id = id;
        this.registeredInformation = registeredInformation;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        if (registeredInformation != null) {
            return registeredInformation.getName();
        } else if (name != null) {
            return name;
        } else {
            return "";
        }
    }

    public void setName(String name) {
        this.name = name;
    }

    public DeviceResponse getRegisteredInformation() {
        return registeredInformation;
    }

    public void setRegisteredInformation(DeviceResponse registeredInformation) {
        this.registeredInformation = registeredInformation;
    }

    public PlayerConfigurationResponse getPlayerConfiguration() {
        return playerConfiguration;
    }

    public void setPlayerConfiguration(PlayerConfigurationResponse playerConfiguration) {
        this.playerConfiguration = playerConfiguration;
    }

    public PlayerService getPlayerService() {
        return playerService;
    }

    public void setPlayerService(PlayerService playerService) {
        this.playerService = playerService;
    }
}
