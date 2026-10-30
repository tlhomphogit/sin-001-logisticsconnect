package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable representation of a cleaned LogisticsConnect hub record.
 */
public record HubRecord(
    @JsonProperty("hubId")
    @JsonAlias("hub_id")
    String hubId,

    @JsonProperty("province")
    String province,

    @JsonProperty("sortingCenter")
    @JsonAlias("sorting_center")
    String sortingCenter,

    @JsonProperty("active")
    Boolean active
) {
}
