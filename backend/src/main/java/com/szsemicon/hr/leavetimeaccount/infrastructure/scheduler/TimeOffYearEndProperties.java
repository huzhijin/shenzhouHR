package com.szsemicon.hr.leavetimeaccount.infrastructure.scheduler;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shenzhouhr.time-off-year-end")
public class TimeOffYearEndProperties {

    private Duration lockLease = Duration.ofMinutes(30);
    private ZoneId zone = ZoneId.of("Asia/Shanghai");
    private String nodeId = "";

    public Duration getLockLease() {
        return lockLease;
    }

    public void setLockLease(Duration lockLease) {
        this.lockLease = lockLease;
    }

    public ZoneId getZone() {
        return zone;
    }

    public void setZone(ZoneId zone) {
        this.zone = zone;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }
}
