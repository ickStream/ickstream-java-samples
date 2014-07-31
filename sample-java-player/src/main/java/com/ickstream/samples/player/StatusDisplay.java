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

import com.googlecode.lanterna.TerminalFacade;
import com.googlecode.lanterna.input.Key;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;
import com.ickstream.player.model.PlayerStatus;

import java.util.*;

public class StatusDisplay {
    /*
     * Console screen used for user interface
     */
    private Screen screen = new Screen(TerminalFacade.createTerminal());

    /*
     * Timer which makes sure the screen is refreshed regularly
     */
    private Timer refreshTimer = new Timer();

    /*
     * Dummy object which we use to handle synchronization to make the code thread safe without having to
     * synchronize whole methods
     */
    private Object syncObject;

    /**
     * Player manager that represents player
     */
    private DummyPlayerManager playerManager;

    /**
     * Player status object that represent the current player status
     */
    private PlayerStatus playerStatus;

    private Map<String, Service> services;

    public StatusDisplay(final Object syncObject) {
        this.syncObject = syncObject;
    }

    public void start(DummyPlayerManager playerManager, PlayerStatus playerStatus, Map<String, Service> services, long refreshRate) {
        this.playerManager = playerManager;
        this.playerStatus = playerStatus;
        this.services = services;
        screen.startScreen();
        // Setup refresh timer to screen are refreshed regularly
        refreshTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                refresh();
            }
        }, 0, refreshRate);
    }

    /**
     * Print information about discovered devices and services on the console screen
     */
    public void refresh() {
        synchronized (syncObject) {
            screen.clear();
            int row = 0;
            if (playerManager != null && playerManager.hasAccessToken()) {
                screen.putString(0, row++, playerManager.getName() + " (Registered)", null, null);
                row++;
            } else if (playerManager != null) {
                screen.putString(0, row++, playerManager.getName() + " (Unregistered)", null, null);
                row++;
            }
            screen.putString(0, row++, "=== Local services available ===", null, null);

            List<Service> sortedServices = new ArrayList<Service>(services.values());
            Collections.sort(sortedServices, new Comparator<Service>() {
                @Override
                public int compare(Service s1, Service s2) {
                    return s1.getName().compareTo(s2.getName());
                }
            });

            if (sortedServices.size() > 0) {
                for (Service service : sortedServices) {
                    Terminal.Color color = Terminal.Color.YELLOW;
                    if (service.getServiceInformation() != null) {
                        color = Terminal.Color.GREEN;
                    }
                    screen.putString(0, row++, "- " + (service.isOnlineService() ? "Online: " : "Local:  ") + service.getName() + " (" + service.getId() + ")", color, null);
                }
            }
            row++;

            if (playerStatus != null) {
                screen.putString(0, row++, "=== Player status ===", null, null);

                if (playerStatus.getPlaying()) {
                    if (playerManager.getCurrentStreamingUrl() != null) {
                        screen.putString(0, row++, "Status: Playing", null, null);
                    } else {
                        screen.putString(0, row++, "Status: Playing (Unable to get streaming url)", Terminal.Color.RED, null);
                    }
                    screen.putString(0, row++, "Track: " + playerStatus.getCurrentPlaylistItem().getText(), null, null);
                    if (playerStatus.getCurrentPlaylistItem().getItemAttributes().has("duration") && !playerStatus.getCurrentPlaylistItem().getItemAttributes().get("duration").isNull()) {
                        screen.putString(0, row++, "Progress: " + playerManager.getSeekPosition().intValue() + "/" + playerStatus.getCurrentPlaylistItem().getItemAttributes().get("duration").asInt() + " seconds", null, null);
                    } else {
                        screen.putString(0, row++, "Progress: " + playerManager.getSeekPosition().intValue() + " seconds", null, null);
                    }
                } else if (playerStatus.getCurrentPlaylistItem() != null) {
                    screen.putString(0, row++, "Status: Stopped", null, null);
                    screen.putString(0, row++, "Track: " + playerStatus.getCurrentPlaylistItem().getText(), null, null);
                    row++;
                } else {
                    screen.putString(0, row++, "Status: Stopped", null, null);
                    row++;
                    row++;
                }
                row++;

                if (playerStatus.getPlaybackQueuePos() != null && playerStatus.getPlaybackQueue().getItems().size() > 0) {
                    screen.putString(0, row++, "=== Playback queue (" + playerStatus.getPlaybackQueue().getItems().size() + " tracks)===", null, null);
                    int startPos = playerStatus.getPlaybackQueuePos() - 7;
                    if (startPos < 0) {
                        startPos = 0;
                    }
                    for (int i = startPos; i < (startPos + 18 - sortedServices.size()) && i < playerStatus.getPlaybackQueue().getItems().size(); i++) {
                        if (i == playerStatus.getPlaybackQueuePos()) {
                            screen.putString(0, row++, playerStatus.getPlaybackQueue().getItems().get(i).getText(), Terminal.Color.GREEN, null);
                        } else {
                            screen.putString(0, row++, playerStatus.getPlaybackQueue().getItems().get(i).getText(), null, null);
                        }
                    }
                }
            }
            row++;
            screen.putString(0, row++, "Click Ctrl+C to exit", null, null);
            screen.completeRefresh();
            screen.setCursorPosition(0, ++row);
        }
    }

    public void waitForExit() throws InterruptedException {
        // Wait for Ctrl+c
        Key key = screen.readInput();
        while (key == null || (!(key.isCtrlPressed() && key.getCharacter() == 'c'))) {
            Thread.sleep(20);
            key = screen.readInput();
        }
    }

    public void shutdown() {
        screen.stopScreen();
        refreshTimer.cancel();
    }
}
