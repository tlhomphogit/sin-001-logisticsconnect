package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransitResponse(
    @JsonProperty("hubId") String hubId,
    @JsonProperty("province") String province,
    @JsonProperty("sortingCenter") String sortingCenter,
    @JsonProperty("active") Boolean active,
    @JsonProperty("stage") int stage,
    @JsonProperty("baseTransitHours") int baseTransitHours,
    @JsonProperty("delayHours") int delayHours,
    @JsonProperty("minEstimatedHours") int minEstimatedHours,
    @JsonProperty("maxEstimatedHours") int maxEstimatedHours,
    @JsonProperty("estimatedArrivalWindow") String estimatedArrivalWindow
) {
}
