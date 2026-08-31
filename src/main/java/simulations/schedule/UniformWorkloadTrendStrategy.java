package simulations.schedule;

import java.util.Random;

/**
 * Uniform workload distribution strategy.
 * Distributes requests evenly throughout the simulation duration with randomized intervals.
 * This prevents large gaps while maintaining overall consistency.
 */
public class UniformWorkloadTrendStrategy implements WorkloadTrendStrategy {
    private final Random random;

    public UniformWorkloadTrendStrategy(Random random) {
        this.random = random;
    }

    /**
     * Distributes the request times uniformly across the simulation duration.
     * Each request is placed at a random position within its designated time slot,
     * ensuring no large gaps between consecutive requests.
     */
    @Override
    public long getRequestTimeMs(int requestIndex, int totalRequests, long simulationDurationMs) {
        if (totalRequests <= 1) {
            // Only one request: place it at a random time in the first half of simulation
            return random.nextLong(simulationDurationMs / 2);
        }

        // Divide the simulation into equal slots for each request
        long slotSize = simulationDurationMs / totalRequests;

        // Calculate the start of this request's time slot
        long slotStart = (long) requestIndex * slotSize;

        // Place the request randomly within its slot
        // This ensures distribution while preventing large gaps
        long randomOffset = random.nextLong(slotSize);

        return slotStart + randomOffset;
    }
}
