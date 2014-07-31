/*
 * Copyright (c) 2014, ickStream GmbH
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *   * Neither the name of ickStream nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.ickstream.samples.player;

import com.fasterxml.jackson.databind.JsonNode;
import com.ickstream.common.ickp2p.*;
import com.ickstream.common.jsonrpc.*;
import com.ickstream.player.model.PlaybackQueue;
import com.ickstream.player.model.PlaybackQueueStorage;
import com.ickstream.player.model.PlayerStatus;
import com.ickstream.player.model.PlayerStatusStorage;
import com.ickstream.player.service.PlayerCommandService;
import com.ickstream.player.service.PlayerNotificationSender;
import com.ickstream.protocol.common.IckStreamTrustManager;
import com.ickstream.protocol.common.NetworkAddressHelper;
import com.ickstream.protocol.common.exception.ServiceException;
import com.ickstream.protocol.common.exception.ServiceTimeoutException;
import com.ickstream.protocol.common.exception.UnauthorizedException;
import com.ickstream.protocol.service.ServiceInformation;
import com.ickstream.protocol.service.content.DeviceContentService;
import com.ickstream.protocol.service.core.CoreService;
import com.ickstream.protocol.service.core.CoreServiceFactory;
import com.ickstream.protocol.service.core.DeviceResponse;
import com.ickstream.protocol.service.core.SetDeviceAddressRequest;
import com.ickstream.protocol.service.player.PlayerService;
import com.ickstream.protocol.service.scrobble.ScrobbleService;
import com.ickstream.protocol.service.scrobble.ScrobbleServiceFactory;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Simple player that is able to store current playback queue and simulate audio playback
 */
public class SamplePlayer extends DiscoveryAdapter implements MessageListener, ServiceUrlManager {
    /**
     * API key, only use this key in this sample client
     * When you develop your own app, you should request a specific API-key for it
     */
    private static final String API_KEY = "474C55EA-C46C-4333-921B-559D87D2A679";

    /**
     * Services discovered (both online and local)
     */
    private final Map<String, Service> availableServices = new HashMap<String, Service>();

    /**
     * Storage of settings
     */
    private final Preferences preferences = Preferences.userNodeForPackage(SamplePlayer.class);

    /**
     * ickStream P2P implementation
     */
    private IckP2p ickP2p = null;

    /**
     * Dummy player manager that simulates playback but never outputs any sound
     */
    private com.ickstream.samples.player.DummyPlayerManager playerManager = null;

    /**
     * Player status object that represent the current player status
     */
    private PlayerStatus playerStatus;

    /*
     * Implementation of Player Protocol
     */
    private PlayerCommandService playerService;

    /**
     * Dummy object which we use to handle synchronization to make the code thread safe without having to
     * synchronize whole methods
     */
    private static final Object syncObject = new Object();

    /**
     * User interface that displays a status display
     */
    private final StatusDisplay statusDisplay = new StatusDisplay(syncObject);

    /**
     * Dummy main which just launch the {@link #run()} method
     *
     * @param args Arguments, takes a user access token as argument
     */
    public static void main(String[] args) throws BackingStoreException, InterruptedException {
        IckStreamTrustManager.init();
        new SamplePlayer().run();
    }


    /**
     * This is where everything happens
     */
    public void run() throws BackingStoreException, InterruptedException {
        // Get previously used device access token from preferences
        String cloudCoreUrl = preferences.get("cloudCoreUrl", CoreServiceFactory.getCoreServiceEndpoint());
        String accessToken = preferences.get("accessToken", null);

        // Detect IP address of current device
        String ipAddress = NetworkAddressHelper.getNetworkAddress();

        // Scrobble service
        ScrobbleService scrobbleService = null;

        DeviceResponse device = null;
        if (accessToken != null) {
            // Get a client class for the Cloud Core service
            CoreService coreService = CoreServiceFactory.getCoreService(cloudCoreUrl, accessToken);

            // Update the IP address in the cloud server, this is done for two reasons:
            // - We want to ensure we have a valid device access token, if we don't this call will fail
            // - We want to update the IP-address in the cloud so the device can be reached from
            //   remote locations when support for this is available
            SetDeviceAddressRequest request = new SetDeviceAddressRequest(ipAddress);
            try {
                // Update device address in cloud
                device = coreService.setDeviceAddress(request);

                // Get reference to scrobbling service
                scrobbleService = ScrobbleServiceFactory.getScrobbleService(cloudCoreUrl, accessToken);

                // Print information about current device
                System.out.println("Current device registered as: " + device.getName() + " (" + device.getId() + ")");
                System.out.println("Using access token: " + accessToken);
            } catch (UnauthorizedException e) {
                System.out.println("Unauthorized access, probably an invalid access token, continue as unregistered");
                preferences.remove("accessToken");
                accessToken = null;
            } catch (ServiceException e) {
                System.err.println("Can't reach the Cloud Core service");
                return;
            } catch (ServiceTimeoutException e) {
                System.err.println("Can't reach the Cloud Core service within specified timeout");
                return;
            }
        } else {
            System.out.println("Current device not yet registered");
        }

        // Setup a shutdown hook so we can cleanly stop ickStream P2P if the process is killed
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }));


        // Setup notification sender proxy
        PlayerNotificationSender notificationSender = new PlayerNotificationSender(new com.ickstream.common.jsonrpc.MessageSender() {
            @Override
            public void sendMessage(String message) {
                try {
                    ickP2p.sendMsg(ServiceType.PLAYER, message.getBytes("UTF-8"));
                    // Let's update player status screen when notifications are generated
                    statusDisplay.refresh();
                } catch (UnsupportedEncodingException e) {
                    // Just ignore, all platforms we support are going to support UTF-8
                    e.printStackTrace();
                } catch (IckP2pException e) {
                    // TODO: Do we need some error handling here ?
                    e.printStackTrace();
                }
            }
        });

        // Setup player status storage manager
        PlayerStatusStorage playerStatusStorage = new PlayerStatusStorage() {
            @Override
            public void store(PlayerStatus status) {
                // Do nothing in this sample player
                // Normally we would write the status to a persistent storage at this point
            }
        };

        // Setup playback queue storage manager
        PlaybackQueueStorage playbackQueueStorage = new PlaybackQueueStorage() {
            @Override
            public void store(PlaybackQueue playbackQueue) {
                // Do nothing in this sample player
                // Normally we would write the playback queue to a persistent storage at this point
            }
        };

        // Just create a non persistent player status object
        // Normally player status and playback queue would be read from a persistent storage at this point
        playerStatus = new PlayerStatus(new PlaybackQueue(playbackQueueStorage));
        playerStatus.setStorage(playerStatusStorage);

        playerManager = new DummyPlayerManager(ipAddress, playerStatus, notificationSender, scrobbleService, null, this);
        if (device != null) {
            playerManager.setName(device.getName());
        } else if (playerManager.getName() == null) {
            playerManager.setName("Sample Player");
        }
        playerService = new PlayerCommandService(API_KEY, playerManager, playerStatus);

        // Setup ickStream P2P module and announce the current device on the network
        ickP2p = new IckP2pJNI();
        // Setup device listener so we get information about new, updated, removed devices on local network
        ickP2p.addDiscoveryListener(this);
        // Setup message listener so we can receive messages from other devices on local network
        ickP2p.addMessageListener(this);
        System.out.println("Initiating discovery on local network...");
        try {
            // Set device name in case not registered
            if (device == null) {
                device = new DeviceResponse();
                device.setId(UUID.randomUUID().toString().toUpperCase());
                device.setName(playerManager.getName());
            }

            // Initialize ickP2p
            ickP2p.create(device.getName(), device.getId(), null, null, null, ServiceType.PLAYER);
            // Add all network interfaces which we want to perform discovery on
            ickP2p.addInterface(ipAddress, null);
            // Start the discovery
            ickP2p.resume();

            // Open status display
            statusDisplay.start(playerManager, playerStatus, availableServices, 1000);

            // Wait for exit command
            statusDisplay.waitForExit();

            // Remove refresh timers
            playerManager.shutdown();

            // Shutdown ickStream P2P module
            shutdown();
        } catch (IckP2pException e) {
            System.err.println("Failed to initialize ickP2p");
            e.printStackTrace();
        }
    }


    /**
     * Shutdown everything
     */
    private void shutdown() {
        // Stop display
        statusDisplay.shutdown();

        // Stop ickStream P2P if it has been started
        if (ickP2p != null) {
            System.out.println("Shutting down...");
            try {
                ickP2p.end();
                System.out.println("ickP2p successfully shutdown");
            } catch (IckP2pException e) {
                System.err.println("Failed to properly shutdown ickP2p");
            }
            ickP2p = null;
        }
    }


    /**
     * When a new device is discovered and connected, we detect if it's a service or player and add
     * it to the appropriate list
     *
     * @param event The discovery information related to the discovered device
     */
    @Override
    public void onConnectedDevice(DiscoveryEvent event) {
        // If discovered device is a service
        if (event.getServices().isType(ServiceType.SERVICE)) {
            final Service service = new Service(event.getDeviceId(), event.getDeviceName());

            synchronized (syncObject) {
                availableServices.put(event.getDeviceId(), service);
            }

            // Request additional information about the service
            getMoreInformationAboutLocalService(service);
        } else {
            // Remove the device from the service list in case it previously reported to offer a service
            synchronized (syncObject) {
                availableServices.remove(event.getDeviceId());
            }
        }

        // Refresh the console user interface
        statusDisplay.refresh();
    }


    /**
     * When a device is removed, we remove it from the lists
     *
     * @param deviceId The identity for the removed device
     */
    @Override
    public void onDisconnectedDevice(String deviceId) {
        // Ensure thread safety as this callback can come from multiple parallel threads
        synchronized (syncObject) {
            availableServices.remove(deviceId);
        }
        // Refresh the console user interface
        statusDisplay.refresh();
    }


    /**
     * Setup Content Access service client for a local service and make an asynchronous call to retrieve more information
     *
     * @param service The service to request more information about
     */
    public void getMoreInformationAboutLocalService(final Service service) {
        // Make an asynchronous call to get more information about the service
        // This call is mainly here to show the concept
        DeviceContentService serviceClient = new DeviceContentService(ServiceType.PLAYER, service.getId(), ickP2p);
        service.setContentService(serviceClient);
        getMoreInformationAboutService(service);
    }


    /**
     * Request more information about a local or online service
     *
     * @param service The service to request more information about
     */
    public void getMoreInformationAboutService(final Service service) {
        service.getContentService().getServiceInformation(new MessageHandlerAdapter<ServiceInformation>() {
            @Override
            public void onMessage(ServiceInformation serviceInformation) {
                synchronized (syncObject) {
                    service.setServiceInformation(serviceInformation);
                }
                statusDisplay.refresh();
            }

            @Override
            public void onError(int code, String message, String data) {
                System.err.println("Error when retrieving information about: " + service.getName() + " (" + service.getId() + ")");
                System.err.println(code + ": " + message + (data != null ? " : " + data : ""));
            }

            @Override
            public void onTimeout() {
                System.err.println("Timeout when retrieving information about: " + service.getName() + " (" + service.getId() + ")");
            }

        });
    }

    /**
     * When a message is received from another device we need to lookup the {@link com.ickstream.protocol.service.content.ContentService} or
     * {@link PlayerService} client instance which is handling it and forward the incoming message to it
     *
     * @param sourceDeviceId    The device which sent the message
     * @param targetDeviceId    The device which the message was sent to (this will always be us)
     * @param targetServiceType The type of service the message was sent to
     * @param message           The message
     */
    @Override
    public void onMessage(String sourceDeviceId, ServiceType sourceServiceType, String targetDeviceId, ServiceType targetServiceType, byte[] message) {
        // If the message is for us, make sure the player service object is processing it
        if (targetServiceType.isType(ServiceType.PLAYER)) {
            StringJsonRpcService jsonService = new StringJsonRpcService(playerService, PlayerCommandService.class);
            String response = null;
            try {
                response = jsonService.handle(new String(message, "UTF-8"));
                if (response != null && response.length() > 0) {
                    ickP2p.sendMsg(sourceDeviceId, sourceServiceType, ServiceType.PLAYER, response.getBytes("UTF-8"));
                }
            } catch (UnsupportedEncodingException e) {
                // Just ignore, all platforms we support are going to support UTF-8
                e.printStackTrace();
            } catch (IckP2pException e) {
                //TODO: Do we need some error handling ?
                e.printStackTrace();
            }
        }
        // Make sure we are also processing responses on requests to local content services
        try {
            JsonHelper jsonHelper = new JsonHelper();
            JsonNode jsonMessage = jsonHelper.stringToObject(new String(message, "UTF-8"), JsonNode.class);
            if (jsonMessage.has("result") && jsonMessage.has("id")) {
                JsonRpcResponse jsonResponse = jsonHelper.jsonToObject(jsonMessage, JsonRpcResponse.class);
                if (jsonResponse != null) {
                    List<JsonRpcResponseHandler> responseHandlers;
                    synchronized (syncObject) {
                        responseHandlers = new ArrayList<JsonRpcResponseHandler>();
                        for (Service service : availableServices.values()) {
                            responseHandlers.add(service.getContentService());
                        }
                    }
                    for (JsonRpcResponseHandler responseHandler : responseHandlers) {
                        if (responseHandler.onResponse(jsonResponse)) {
                            break;
                        }
                    }
                }
            }
        } catch (UnsupportedEncodingException e) {
            // Just ignore, UTF-8 is supported on Android
            e.printStackTrace();
        }
    }


    /**
     * Get service url for the specified service
     *
     * @param service The identity of the service
     * @return The service url or null if no service url is available
     */
    @Override
    public String getServiceUrl(String service) {
        // TODO: This should be extended to also handle online services but currently service:// urls only exists for local services
        Service serviceObj;
        synchronized (syncObject) {
            serviceObj = availableServices.get(service);
        }
        if (serviceObj != null && serviceObj.getServiceInformation() != null) {
            return serviceObj.getServiceInformation().getServiceUrl();
        }
        return null;
    }
}
