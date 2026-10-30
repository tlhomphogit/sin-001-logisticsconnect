package co.wethinkcode.logisticsconnect.mq;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DelayStageEvent(
    String hubId,
    int stage,
    long timestamp
) {}
