package co.wethinkcode.logisticsconnect.mq;

public interface DelayStagePublisher extends AutoCloseable {
    /**
     * Publishes a delay stage change event to the message broker.
     *
     * @param hubId canonical uppercase hub ID
     * @param stage integer stage (0-8)
     * @param timestamp positive epoch milliseconds
     * @throws Exception if message publication fails
     */
    void publish(String hubId, int stage, long timestamp) throws Exception;

    @Override
    default void close() throws Exception {}
}
