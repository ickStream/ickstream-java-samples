/*
 * Copyright (C) 2013 ickStream GmbH
 * All rights reserved
 */

package com.ickstream.samples.controller;

import com.googlecode.lanterna.TerminalFacade;
import com.googlecode.lanterna.input.Key;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;
import com.ickstream.common.ickdiscovery.*;
import com.ickstream.common.jsonrpc.JsonHelper;
import com.ickstream.common.jsonrpc.JsonRpcResponse;
import com.ickstream.common.jsonrpc.MessageHandlerAdapter;
import com.ickstream.protocol.common.ChunkedRequest;
import com.ickstream.protocol.common.NetworkAddressHelper;
import com.ickstream.protocol.common.exception.ServiceException;
import com.ickstream.protocol.common.exception.ServiceTimeoutException;
import com.ickstream.protocol.common.exception.UnauthorizedException;
import com.ickstream.protocol.service.ServiceInformation;
import com.ickstream.protocol.service.content.DeviceContentService;
import com.ickstream.protocol.service.content.HttpContentService;
import com.ickstream.protocol.service.core.*;
import com.ickstream.protocol.service.player.PlayerConfigurationResponse;
import com.ickstream.protocol.service.player.PlayerService;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Simple controller that shows how to discover devices and services
 */
public class SampleController implements DeviceListener, MessageListener {
    /**
     * API key, only use this key in this sample client
     * When you develop your own app, you should request a specific API-key for it
     */
    private static final String API_KEY = "8DB02DEB-9442-4D75-B444-52FDCC01C9E3";

    /**
     * Players discovered
     */
    private final Map<String, Device> availablePlayers = new HashMap<String, Device>();

    /**
     * Services discovered (both online and local)
     */
    private final Map<String, Service> availableServices = new HashMap<String, Service>();

    /**
     * All devices registered in Cloud Core service
     */
    private final Map<String, DeviceResponse> registeredDevices = new HashMap<String, DeviceResponse>();

    /**
     * Storage of settings
     */
    private final Preferences preferences = Preferences.userNodeForPackage(SampleController.class);

    /**
     * Console screen used for user interface
     */
    private final Screen screen = new Screen(TerminalFacade.createTerminal());

    /**
     * Current user
     */
    private GetUserResponse user = null;

    /**
     * ickStream P2P implementation
     */
    private IckDiscovery ickDiscovery = null;

    /**
     * Dummy object which we use to handle synchronization to make the code thread safe without having to
     * synchronize whole methods
     */
    private static final Object syncObject = new Object();


    /**
     * Dummy main which just launch the {@link #run(String)} method
     *
     * @param args Arguments, takes a user access token as argument
     */
    public static void main(String[] args) throws BackingStoreException, InterruptedException {
        if (args.length == 0) {
            new SampleController().run(null);
        } else {
            new SampleController().run(args[0]);
        }
    }


    /**
     * This is where everything happens
     *
     * @param userAccessToken A user access token or null if previously registered device access token should be used
     */
    public void run(String userAccessToken) throws BackingStoreException, InterruptedException {
        // Get previously used device access token from preferences
        String deviceAccessToken = preferences.get("accessToken", null);
        String accessToken = deviceAccessToken;

        if (userAccessToken == null && deviceAccessToken == null) {
            // We exit if no user access token has been specified and no device access token exists
            System.err.println("No access token available, specify a user access token as parameter");
            return;
        } else if (userAccessToken != null) {
            // We use the user access token specified as input
            accessToken = userAccessToken;
        }

        System.out.println("Using access token: " + accessToken);

        // Get a client class for the Cloud Core service
        CoreService coreService = CoreServiceFactory.getCoreService(accessToken);

        try {
            // Get information about current user
            user = coreService.getUser();
        } catch (UnauthorizedException e) {
            System.err.println("Unauthorized access, probably an invalid access token");
            preferences.remove("accessToken");
            return;
        } catch (ServiceException e) {
            System.err.println("Can't reach the Cloud Core service");
            return;
        } catch (ServiceTimeoutException e) {
            System.err.println("Can't reach the Cloud Core service within specified timeout");
            return;
        }

        System.out.println("Welcome " + user.getName());

        // Detect IP address of current device
        String ipAddress = NetworkAddressHelper.getNetworkAddress();

        DeviceResponse device;
        if (deviceAccessToken == null) {
            // If we don't have a device access token we need to register the current device to get one
            device = registerDevice(coreService, ipAddress);
            if (device instanceof AddDeviceResponse) {
                deviceAccessToken = ((AddDeviceResponse) device).getAccessToken();
            }
        } else {
            // Update the IP address in the cloud server, this is done for two reasons:
            // - We want to ensure we have a valid device access token, if we don't this call will fail
            // - We want to update the IP-address in the cloud so the device can be reached from
            //   remote locations when support for this is available
            SetDeviceAddressRequest request = new SetDeviceAddressRequest(ipAddress);
            try {
                device = coreService.setDeviceAddress(request);
            } catch (UnauthorizedException e) {
                if (userAccessToken != null) {
                    device = registerDevice(coreService, ipAddress);
                    if (device instanceof AddDeviceResponse) {
                        deviceAccessToken = ((AddDeviceResponse) device).getAccessToken();
                    }
                } else {
                    System.err.println("Unauthorized access, probably an invalid access token");
                    return;
                }
            } catch (ServiceException e) {
                System.err.println("Can't reach the Cloud Core service");
                return;
            } catch (ServiceTimeoutException e) {
                System.err.println("Can't reach the Cloud Core service within specified timeout");
                return;
            }
        }
        // Make sure we have configured the Cloud Core service client with a device access token
        coreService.setAccessToken(deviceAccessToken);
        System.out.println("Using access token: " + deviceAccessToken);

        System.out.println("Current device is: " + device.getName() + " (" + device.getId() + ")");

        // Find all devices registered in the Cloud Core service for the current user
        try {
            FindDevicesResponse response = coreService.findDevices(new ChunkedRequest());
            for (DeviceResponse deviceResponse : response.getItems()) {
                registeredDevices.put(deviceResponse.getId(), deviceResponse);
            }
        } catch (ServiceException e) {
            System.err.println("Unable to retrieve registered devices");
            return;
        } catch (ServiceTimeoutException e) {
            System.err.println("Timeout when retrieving registered devices");
            return;
        }

        // Find all services available in the Cloud Core service for the current user
        try {
            FindServicesResponse response = coreService.findServices(new FindServicesRequest("content"));
            for (ServiceResponse serviceResponse : response.getItems()) {
                final Service service = new Service(serviceResponse);
                availableServices.put(serviceResponse.getId(), service);

                // Request additional information about the service
                // This is mainly here to show the concept, it doesn't really return any more information than
                // what we already have except for some image urls
                getMoreInformationAboutOnlineService(service, serviceResponse.getUrl(), deviceAccessToken);
            }
        } catch (ServiceException e) {
            System.err.println("Unable to retrieve registered devices");
            return;
        } catch (ServiceTimeoutException e) {
            System.err.println("Timeout when retrieving registered devices");
            return;
        }

        // Setup a shutdown hook so we can cleanly stop ickStream P2P if the process is killed
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }));

        // Setup ickStream P2P module and announce the current device on the network
        ickDiscovery = new IckDiscoveryJNI();
        // Setup device listener so we get information about new, updated, removed devices on local network
        ickDiscovery.addDeviceListener(this);
        // Setup message listener so we can receive messages from other devices on local network
        ickDiscovery.addMessageListener(this);
        System.out.println("Initiating discovery on local network...");
        ickDiscovery.initDiscovery(device.getId(), ipAddress, device.getName(), null);
        ickDiscovery.addService(ServiceType.CONTROLLER);

        // Initialize console and print information about discovered devices and services
        screen.startScreen();
        printDevicesAndServices();

        // Wait for Ctrl+c
        Key key = screen.readInput();
        while (key == null || (!(key.isCtrlPressed() && key.getCharacter() == 'c'))) {
            Thread.sleep(20);
            key = screen.readInput();
        }

        // Shutdown ickStream P2P module
        shutdown();
    }


    /**
     * Shutdown everything
     */
    private void shutdown() {
        // Stop console screen
        screen.stopScreen();

        // Stop ickStream P2P if it has been started
        if (ickDiscovery != null) {
            System.out.println("Shutting down...");
            ickDiscovery.endDiscovery();
            ickDiscovery = null;
        }
    }

    /**
     * Register the current device
     *
     * @param coreService Cloud Core client to register the device in
     * @param ipAddress   IP-address of current device
     * @return The registered device or null if registration failed
     */
    public DeviceResponse registerDevice(CoreService coreService, String ipAddress) throws BackingStoreException {
        String macAddress = NetworkAddressHelper.getNetworkHardwareAddress();
        AddDeviceWithHardwareIdRequest addDeviceRequest = new AddDeviceWithHardwareIdRequest("SampleController", "My Sample Controller", ipAddress, API_KEY, macAddress);
        try {
            AddDeviceResponse device = coreService.addDeviceWithHardwareId(addDeviceRequest);
            preferences.put("accessToken", device.getAccessToken());
            preferences.flush();
            return device;
        } catch (UnauthorizedException e1) {
            System.err.println("Unauthorized access, probably an invalid API key");
            return null;
        } catch (ServiceException e1) {
            System.err.println("Error when registering the device");
            System.err.println(e1.getCode() + ": " + e1.getMessage());
            return null;
        } catch (ServiceTimeoutException e1) {
            System.err.println("Timeout when registering the device");
            return null;
        }
    }


    /**
     * Print information about discovered devices and services on the console screen
     */
    public void printDevicesAndServices() {
        synchronized (syncObject) {
            screen.clear();
            int row = 0;
            screen.putString(0, row++, "=== Devices and services available ===", null, null);
            screen.putString(0, row++, "User: " + user.getName(), null, null);

            List<Device> sortedPlayers = new ArrayList<Device>(availablePlayers.values());
            Collections.sort(sortedPlayers, new Comparator<Device>() {
                @Override
                public int compare(Device d1, Device d2) {
                    return d1.getName().compareTo(d2.getName());
                }
            });

            if (sortedPlayers.size() > 0) {
                row++;
                screen.putString(0, row++, "Discovered players: ", null, null);
                for (Device player : sortedPlayers) {
                    Terminal.Color color = Terminal.Color.YELLOW;
                    if (player.getPlayerConfiguration() != null) {
                        color = Terminal.Color.GREEN;
                    }
                    screen.putString(0, row++, "- " + player.getName() + " (" + player.getId() + ")" + (player.getRegisteredInformation() == null ? " (Unregistered)" : ""), color, null);
                }
            }

            List<Service> sortedServices = new ArrayList<Service>(availableServices.values());
            Collections.sort(sortedServices, new Comparator<Service>() {
                @Override
                public int compare(Service s1, Service s2) {
                    return s1.getName().compareTo(s2.getName());
                }
            });

            if (sortedServices.size() > 0) {
                row++;
                screen.putString(0, row++, "Discovered services: ", null, null);
                for (Service service : sortedServices) {
                    Terminal.Color color = Terminal.Color.YELLOW;
                    if (service.getServiceInformation() != null) {
                        color = Terminal.Color.GREEN;
                    }
                    screen.putString(0, row++, "- " + (service.isOnlineService() ? "Online: " : "Local:  ") + service.getName() + " (" + service.getId() + ")", color, null);
                }
            }
            row++;
            screen.putString(0, row++, "Click Ctrl+C to exit", null, null);
            screen.completeRefresh();
            screen.setCursorPosition(0, ++row);
        }
    }


    /**
     * When a new device is discovered, we detect if it's a service or player and add
     * it to the appropriate list
     *
     * @param deviceId   The identity of the device
     * @param deviceName The name of the device or null if it doesn't have a name
     * @param services   The service which the device offers
     */
    @Override
    public void onDeviceAdded(String deviceId, String deviceName, ServiceType services) {
        // If discovered device is a service
        if (services.isType(ServiceType.SERVICE)) {
            final Service service = new Service(deviceId, deviceName);

            synchronized (syncObject) {
                availableServices.put(deviceId, service);
            }

            // Request additional information about the service
            getMoreInformationAboutLocalService(service);
        } else {
            // Remove the device from the service list in case it previously reported to offer a service
            synchronized (syncObject) {
                availableServices.remove(deviceId);
            }
        }

        // If discovered device is a player
        if (services.isType(ServiceType.PLAYER)) {
            // Lookup the device among devices registered in the Cloud Code service so we can
            // indicate if it's registered or not
            DeviceResponse registeredDevice = registeredDevices.get(deviceId);
            if (registeredDevice != null) {
                Device player = new Device(deviceId, registeredDevice);
                synchronized (syncObject) {
                    availablePlayers.put(deviceId, player);
                }
                // Request additional information about the player
                getMoreInformationAboutPlayer(player);
            } else {
                Device player = new Device(deviceId, deviceName);
                synchronized (syncObject) {
                    availablePlayers.put(deviceId, player);
                }
                // Request additional information about the player
                getMoreInformationAboutPlayer(player);
            }
        } else {
            // Remove the device from the player list in case it previously reported to offer a player service
            availablePlayers.remove(deviceId);
        }

        // Refresh the console user interface
        printDevicesAndServices();
    }


    /**
     * When a device is updated, we do the same as when a new device is added.
     * The reason for this is a temporary workaround for a behavior in ickStream P2P where update indications
     * can be generated for completely new devices
     *
     * @param deviceId   The identity of the discovered device
     * @param deviceName The name of the discovered device or null if it doesn't have a name
     * @param services   The services which the discovered device offers
     */
    @Override
    public void onDeviceUpdated(String deviceId, String deviceName, ServiceType services) {
        onDeviceAdded(deviceId, deviceName, services);
    }


    /**
     * When a device is removed, we remove it from the lists
     *
     * @param deviceId The identity for the removed device
     */
    @Override
    public void onDeviceRemoved(String deviceId) {
        // Ensure thread safety as this callback can come from multiple parallel threads
        synchronized (syncObject) {
            availablePlayers.remove(deviceId);
            availableServices.remove(deviceId);
        }
        // Refresh the console user interface
        printDevicesAndServices();
    }

    /**
     * Setup Player service client for a player and make an asynchronous call to retrieve more information
     *
     * @param player The player to request more information about
     */
    public void getMoreInformationAboutPlayer(final Device player) {
        // Make an asynchronous call to get more information about the service
        // This call is mainly here to show the concept
        PlayerService playerService = new PlayerService(ickDiscovery, player.getId());
        player.setPlayerService(playerService);

        playerService.getPlayerConfiguration(new MessageHandlerAdapter<PlayerConfigurationResponse>() {
            @Override
            public void onMessage(PlayerConfigurationResponse playerConfiguration) {
                synchronized (syncObject) {
                    player.setPlayerConfiguration(playerConfiguration);
                }
                printDevicesAndServices();
            }

            @Override
            public void onError(int code, String message, String data) {
                System.err.println("Error when retrieving information about: " + player.getName() + " (" + player.getId() + ")");
                System.err.println(code + ": " + message + (data != null ? " : " + data : ""));
            }

            @Override
            public void onTimeout() {
                System.err.println("Timeout when retrieving information about: " + player.getName() + " (" + player.getId() + ")");
            }

        });
    }

    /**
     * Setup Content Access service client for an online service and make an asynchronous call to retrieve more information
     *
     * @param service           The service to request more information about
     * @param url               The url of the service
     * @param deviceAccessToken The device access token to use when accessing the service
     */
    public void getMoreInformationAboutOnlineService(final Service service, String url, String deviceAccessToken) {
        // Make an asynchronous call to get more information about the service
        // This call is mainly here to show the concept
        HttpContentService serviceClient = new HttpContentService(service.getId(), url);
        serviceClient.setAccessToken(deviceAccessToken);
        service.setContentService(serviceClient);
        getMoreInformationAboutService(service);
    }

    /**
     * Setup Content Access service client for a local service and make an asynchronous call to retrieve more information
     *
     * @param service The service to request more information about
     */
    public void getMoreInformationAboutLocalService(final Service service) {
        // Make an asynchronous call to get more information about the service
        // This call is mainly here to show the concept
        DeviceContentService serviceClient = new DeviceContentService(service.getId(), ickDiscovery);
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
                printDevicesAndServices();
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
    public void onMessage(String sourceDeviceId, String targetDeviceId, ServiceType targetServiceType, byte[] message) {
        Device player = null;
        Service service = null;

        // If the message is for us, lookup and player or server with the expected device identity
        if (targetServiceType.isType(ServiceType.CONTROLLER)) {
            synchronized (syncObject) {
                player = availablePlayers.get(sourceDeviceId);
                service = availableServices.get(sourceDeviceId);
            }
        }
        try {
            // Convert the message to a JSON-RPC response
            JsonRpcResponse response = new JsonHelper().stringToObject(new String(message, "UTF-8"), JsonRpcResponse.class);
            if (response != null) {
                boolean handled = false;

                // Try to forward the message to the player if we had a matching player
                if (player != null) {
                    handled = player.getPlayerService().onResponse(response);
                }

                // If the player didn't exist or didn't handle it, forward the message to the matching service instead
                if (service != null && !handled) {
                    service.getContentService().onResponse(response);
                }
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
}
