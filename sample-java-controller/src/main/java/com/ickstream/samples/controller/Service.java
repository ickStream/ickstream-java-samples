/*
 * Copyright (C) 2013 ickStream GmbH
 * All rights reserved
 */

package com.ickstream.samples.controller;

import com.ickstream.protocol.service.ServiceInformation;
import com.ickstream.protocol.service.content.ContentService;
import com.ickstream.protocol.service.core.ServiceResponse;

/**
 * Representation of a service, either local or online
 */
public class Service {
    /**
     * Identity of the service
     */
    private String id;

    /**
     * Name of the service, this might be null for local services
     */
    private String name;

    /**
     * More information about the service in case it's an online service
     */
    private ServiceResponse onlineService;

    /**
     * More information about the service
     */
    private ServiceInformation serviceInformation;

    /**
     * Content Access service client
     */
    private ContentService contentService;


    public Service(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public Service(ServiceResponse onlineService) {
        this(onlineService.getId(), onlineService.getName());
        this.onlineService = onlineService;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        if (name != null) {
            return name;
        } else {
            return "";
        }
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isOnlineService() {
        return onlineService != null;
    }

    public ServiceInformation getServiceInformation() {
        return serviceInformation;
    }

    public void setServiceInformation(ServiceInformation serviceInformation) {
        this.serviceInformation = serviceInformation;
    }

    public ContentService getContentService() {
        return contentService;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }
}
