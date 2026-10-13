package com.szsemicon.hr.reporting.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shenzhouhr.map")
public class MapBasemapProperties {

    private String basemapProvider = "";
    private String basemapKey = "";

    public String getBasemapProvider() {
        return basemapProvider;
    }

    public void setBasemapProvider(String basemapProvider) {
        this.basemapProvider = basemapProvider == null ? "" : basemapProvider;
    }

    public String getBasemapKey() {
        return basemapKey;
    }

    public void setBasemapKey(String basemapKey) {
        this.basemapKey = basemapKey == null ? "" : basemapKey;
    }

    public boolean configured() {
        return "AMAP".equalsIgnoreCase(basemapProvider.trim()) && !basemapKey.isBlank();
    }
}
