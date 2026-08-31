package simulations.schedule;

/**
 * Strategy interface for different workload distribution patterns.
 * Implementations define how requests should be distributed over time during the simulation.
 * This allows for easy extension to support different workload trends in the future.
 */
public interface WorkloadTrendStrategy {
    /**
     * Calculate the time in milliseconds when a particular request should be sent.
     *
     * @param requestIndex    0-based index of the request (0 = first request, 1 = second, etc.)
     * @param totalRequests   total number of requests this user will send
     * @param simulationDurationMs total duration of the simulation in milliseconds
     * @return time in milliseconds from the start of the simulation when this request should be sent
     */
    long getRequestTimeMs(int requestIndex, int totalRequests, long simulationDurationMs);
}
