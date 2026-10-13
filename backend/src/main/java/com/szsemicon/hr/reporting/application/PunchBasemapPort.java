package com.szsemicon.hr.reporting.application;

import java.math.BigDecimal;
import java.util.Optional;

/** Renders a basemap image for a WGS84 point; empty when unavailable. */
public interface PunchBasemapPort {

    Optional<String> renderWgs84(BigDecimal longitude, BigDecimal latitude);
}
