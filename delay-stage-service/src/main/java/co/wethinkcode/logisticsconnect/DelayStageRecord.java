package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DelayStageRecord(
    @JsonProperty("hubId") String hubId,
    @JsonProperty("stage") int stage
) {
}
