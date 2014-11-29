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

import com.ickstream.common.jsonrpc.JsonHelper;
import com.ickstream.common.jsonrpc.MessageLogger;
import com.ickstream.player.model.PlaybackQueueItemInstance;
import com.ickstream.player.model.PlayerStatus;
import com.ickstream.player.service.PlayerCommandService;
import com.ickstream.player.service.PlayerManager;
import com.ickstream.player.service.PlayerNotificationSender;
import com.ickstream.protocol.common.NetworkAddressHelper;
import com.ickstream.protocol.common.data.ContentItem;
import com.ickstream.protocol.common.data.StreamingReference;
import com.ickstream.protocol.common.data.TrackAttributes;
import com.ickstream.protocol.common.exception.ServiceException;
import com.ickstream.protocol.common.exception.ServiceTimeoutException;
import com.ickstream.protocol.service.content.ContentService;
import com.ickstream.protocol.service.content.ContentServiceFactory;
import com.ickstream.protocol.service.content.GetItemStreamingRefRequest;
import com.ickstream.protocol.service.core.CoreService;
import com.ickstream.protocol.service.core.CoreServiceFactory;
import com.ickstream.protocol.service.core.DeviceResponse;
import com.ickstream.protocol.service.core.SetDeviceAddressRequest;
import com.ickstream.protocol.service.player.*;
import com.ickstream.protocol.service.scrobble.PlayedItem;
import com.ickstream.protocol.service.scrobble.ScrobbleService;
import com.ickstream.protocol.service.scrobble.ScrobbleServiceFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class DummyPlayerManager implements PlayerManager {
    private Double volume = 0.5d;
    private Double seekPosition = 0.0;
    private PlayerNotificationSender notificationSender;
    private String hardwareId;
    private Timer playingProgressTimer;
    private PlayerStatus playerStatus;
    private JsonHelper jsonHelper = new JsonHelper();
    private ScrobbleService scrobbleService;
    private PlaybackQueueItem playingTrack;
    private StreamingReference playingStreamingRef;
    private MessageLogger messageLogger;
    private ServiceUrlManager serviceUrlManager;
    private final Object syncObject;

    public DummyPlayerManager(String hardwareId, PlayerStatus playerStatus, PlayerNotificationSender notificationSender, ScrobbleService scrobbleService, MessageLogger messageLogger, ServiceUrlManager serviceUrlManager, Object syncObject) {
        this.notificationSender = notificationSender;
        this.hardwareId = hardwareId;
        this.playerStatus = playerStatus;
        this.scrobbleService = scrobbleService;
        this.messageLogger = messageLogger;
        this.serviceUrlManager = serviceUrlManager;
        //TODO: We should implement synchronization support based on the syncObject
        this.syncObject = syncObject;
    }

    public void shutdown() {
        if(playingProgressTimer != null) {
            playingProgressTimer.cancel();
        }
    }

    public String getCurrentStreamingUrl() {
        if(playingStreamingRef != null) {
            return playingStreamingRef.getUrl();
        }else {
            return null;
        }
    }

    private void reportTrackAsPlayed(PlaybackQueueItem playlistItem) {
        if (scrobbleService != null) {
            ContentItem playedItem = new ContentItem();
            playedItem.setId(playlistItem.getId());
            playedItem.setText(playlistItem.getText());
            playedItem.setImage(playlistItem.getImage());
            playedItem.setType(playlistItem.getType());
            playedItem.setStreamingRefs(playlistItem.getStreamingRefs());
            playedItem.setItemAttributes(playlistItem.getItemAttributes());
            try {
                scrobbleService.playedTrack(new PlayedItem(System.currentTimeMillis(), null, playedItem));
            } catch (ServiceException e) {
                e.printStackTrace();
                //TODO: Some error handling ?
            } catch (ServiceTimeoutException e) {
                e.printStackTrace();
                //TODO: Some error handling ?
            }
        }
    }

    @Override
    public void setAccessToken(String accessToken) {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        if (accessToken != null) {
            preferences.put("accessToken", accessToken);
        } else {
            preferences.remove("accessToken");
            preferences.remove("userId");
        }
        try {
            preferences.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }
        if (accessToken != null) {
            CoreService coreService = CoreServiceFactory.getCoreService(getCloudCoreUrl(), accessToken, messageLogger);
            SetDeviceAddressRequest request = new SetDeviceAddressRequest(NetworkAddressHelper.getNetworkAddress());
            try {
                DeviceResponse deviceResponse = coreService.setDeviceAddress(request);
                if (deviceResponse.getName() != null) {
                    setName(deviceResponse.getName());
                }
                scrobbleService = ScrobbleServiceFactory.getScrobbleService(getCloudCoreUrl(), accessToken, messageLogger);
            } catch (ServiceException e) {
                e.printStackTrace();
            } catch (ServiceTimeoutException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setUserId(String userId) {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        preferences.put("userId", userId);
        try {
            preferences.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getUserId() {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        return preferences.get("userId", null);
    }

    @Override
    public Boolean hasAccessToken() {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        String accessToken = preferences.get("accessToken", null);
        return accessToken != null;
    }

    @Override
    public String getCloudCoreUrl() {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        return preferences.get("cloudCoreUrl", CoreServiceFactory.getCoreServiceEndpoint());
    }

    @Override
    public void setCloudCoreUrl(String cloudCoreUrl) {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        String previousCloudCoreUrl = getCloudCoreUrl();
        if (!previousCloudCoreUrl.equals(cloudCoreUrl)) {
            preferences.remove("accessToken");
            preferences.remove("userId");
            preferences.put("cloudCoreUrl", cloudCoreUrl);
            try {
                preferences.flush();
            } catch (BackingStoreException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setName(String name) {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        preferences.put("playerName", name);
        try {
            preferences.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getName() {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        return preferences.get("playerName", null);
    }

    @Override
    public String getModel() {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        return preferences.get("playerModel", null);
    }

    @Override
    public void setModel(String model) {
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        preferences.put("playerModel", model);
        try {
            preferences.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getHardwareId() {
        return hardwareId;
    }

    @Override
    public Boolean play() {
        PlaybackQueueItem item = playerStatus.getCurrentPlaylistItem();
        if (item != null) {
            if (playingTrack != null && !item.equals(playingTrack)) {
                reportTrackAsPlayed(playingTrack);
                playingTrack = null;
            }
            if (playingProgressTimer != null) {
                playingProgressTimer.cancel();
            }
            playingProgressTimer = new Timer();
            if (playerStatus.getSeekPos() != null) {
                seekPosition = playerStatus.getSeekPos();
            }
            playerStatus.setSeekPos(seekPosition);
            playerStatus.setPlaying(true);

            // Make sure we have a suitable streaming url in case this is a new track
            if(playingTrack == null) {
                if(item.getStreamingRefs() != null && item.getStreamingRefs().size()>0) {
                    playingStreamingRef = item.getStreamingRefs().get(0);
                }else if(item.getStreamingRefs() == null) {
                    playingStreamingRef = retrieveItemStreamingRef(item.getId());
                }else {
                    playingStreamingRef = null;
                }
                if(playingStreamingRef != null) {
                    try {
                        URI uri = new URI(playingStreamingRef.getUrl());
                        if (uri.getScheme().equals("service")) {
                            String serviceUrl = serviceUrlManager.getServiceUrl(uri.getAuthority());
                            if (serviceUrl != null) {
                                playingStreamingRef.setUrl(serviceUrl + uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : "") + (uri.getFragment() != null ? "#" + uri.getFragment() : ""));
                            }else {
                                System.err.println("Unable to resolve service url for service: "+uri.getAuthority());
                                playingStreamingRef = null;
                            }
                        }
                    } catch (URISyntaxException e) {
                        System.err.println("Invalid url: "+playingStreamingRef.getUrl());
                        e.printStackTrace();
                        playingStreamingRef = null;
                    }
                }
            }
            playingTrack = item;
            playingProgressTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    seekPosition += 1.0;
                    if (playerStatus.getCurrentPlaylistItem() != null) {
                        if (playerStatus.getCurrentPlaylistItem().getItemAttributes() != null) {
                            TrackAttributes trackAttributes = jsonHelper.jsonToObject(playerStatus.getCurrentPlaylistItem().getItemAttributes(), TrackAttributes.class);
                            if (trackAttributes != null && trackAttributes.getDuration() != null && trackAttributes.getDuration() < seekPosition) {
                                reportTrackAsPlayed(playerStatus.getCurrentPlaylistItem());
                                playingTrack = null;
                                if (playerStatus.getPlaybackQueuePos() != null && playerStatus.getPlaybackQueuePos() < playerStatus.getPlaybackQueue().getItems().size() - 1) {
                                    playerStatus.setPlaybackQueuePos(playerStatus.getPlaybackQueuePos() + 1);
                                    playerStatus.setSeekPos(0.0);
                                    play();
                                } else {
                                    playerStatus.setPlaybackQueuePos(0);
                                    playerStatus.setSeekPos(0.0);
                                    seekPosition = 0.0;
                                    if (playerStatus.getPlaybackQueueMode().equals(PlaybackQueueMode.QUEUE_REPEAT_SHUFFLE)) {
                                        List<PlaybackQueueItemInstance> playlistItems = playerStatus.getPlaybackQueue().getItems();
                                        Collections.shuffle(playlistItems);
                                        playerStatus.getPlaybackQueue().setItems(playlistItems);
                                        sendPlaylistChangedNotification();
                                    }
                                    if (playerStatus.getPlaybackQueueMode().equals(PlaybackQueueMode.QUEUE)) {
                                        pause();
                                    } else {
                                        play();
                                    }
                                }
                            }
                        }
                    }
                }
            }, 0, 1000);
            sendPlayerStatusChangedNotification();
            return true;
        } else {
            return false;
        }
    }

    public StreamingReference retrieveItemStreamingRef(String trackId) {
        String service = trackId.substring(0, trackId.indexOf(":"));
        Preferences preferences = Preferences.userNodeForPackage(this.getClass());
        String cloudCoreUrl = preferences.get("cloudCoreUrl", CoreServiceFactory.getCoreServiceEndpoint());
        String accessToken = preferences.get("accessToken", null);

        // Get a client class for the Content service
        ContentService contentService = ContentServiceFactory.getContentService(service, cloudCoreUrl, accessToken);
        if (contentService != null) {
            try {
                return contentService.getItemStreamingRef(new GetItemStreamingRefRequest(trackId), 15000);
            } catch (ServiceException e) {
                e.printStackTrace();
            } catch (ServiceTimeoutException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public void sendPlayerStatusChangedNotification() {
        PlayerStatusResponse notification = new PlayerStatusResponse();
        notification.setPlaying(playerStatus.getPlaying());
        notification.setPlaybackQueuePos(playerStatus.getPlaybackQueuePos());
        if (playerStatus.getPlaybackQueuePos() != null && playerStatus.getPlaying()) {
            playerStatus.setSeekPos(getSeekPosition());
        }
        notification.setSeekPos(playerStatus.getSeekPos());
        if (playerStatus.getPlaybackQueue().getItems().size() > 0) {
            notification.setTrack(PlayerCommandService.createPlaybackQueueItem(playerStatus.getPlaybackQueue().getItems().get(playerStatus.getPlaybackQueuePos())));
        }
        if (!playerStatus.getMuted()) {
            notification.setVolumeLevel(getVolume());
        } else {
            notification.setVolumeLevel(playerStatus.getVolumeLevel());
        }
        notification.setMuted(playerStatus.getMuted());
        notification.setPlaybackQueueMode(playerStatus.getPlaybackQueueMode());
        if (hasAccessToken()) {
            notification.setCloudCoreStatus(CloudCoreStatus.REGISTERED);
            notification.setUserId(getUserId());
        } else {
            notification.setCloudCoreStatus(CloudCoreStatus.UNREGISTERED);
        }
        notificationSender.playerStatusChanged(notification);
    }

    public void sendPlaylistChangedNotification() {
        notificationSender.playbackQueueChanged(
                new PlaybackQueueChangedNotification(
                        playerStatus.getPlaybackQueue().getId(),
                        playerStatus.getPlaybackQueue().getName(),
                        playerStatus.getPlaybackQueue().getItems().size(),
                        playerStatus.getPlaybackQueue().getChangedTimestamp()));
    }

    @Override
    public Boolean pause() {
        if (playingProgressTimer != null) {
            playingProgressTimer.cancel();
        }
        playingProgressTimer = null;
        playerStatus.setSeekPos(getSeekPosition());
        playerStatus.setPlaying(false);
        if (playingTrack != null) {
            reportTrackAsPlayed(playingTrack);
        }
        sendPlayerStatusChangedNotification();
        return true;
    }

    @Override
    public Double getVolume() {
        return volume;
    }

    @Override
    public void setVolume(Double volume) {
        if (volume > 1.0) {
            volume = 1.0;
        } else if (volume < 0) {
            volume = 0.0;
        }
        this.volume = volume;
    }

    @Override
    public Double getSeekPosition() {
        return seekPosition;
    }

    @Override
    public void setSeekPosition(Double seekPos) {
        this.seekPosition = seekPos;
    }
}
