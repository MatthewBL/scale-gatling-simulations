package simulations.schedule;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the request schedule for a single user.
 * Contains the precise timing of when each request should be sent during the simulation.
 */
public class UserWorkloadSchedule {
    private final int userIndex;
    private final List<Long> requestTimingsMs; // Milliseconds from simulation start
    private final int targetRequests;

    public UserWorkloadSchedule(int userIndex, int targetRequests) {
        this.userIndex = userIndex;
        this.targetRequests = targetRequests;
        this.requestTimingsMs = new ArrayList<>(targetRequests);
    }

    /**
     * Add a request timing to this user's schedule.
     *
     * @param timeMs milliseconds from the start of the simulation
     */
    public void addRequestTiming(long timeMs) {
        requestTimingsMs.add(timeMs);
    }

    public int getUserIndex() {
        return userIndex;
    }

    public int getTargetRequests() {
        return targetRequests;
    }

    /**
     * Get all request timings in milliseconds from simulation start.
     */
    public List<Long> getRequestTimings() {
        return requestTimingsMs;
    }

    /**
     * Get the delay in milliseconds before the first request should be sent.
     */
    public long getFirstRequestDelayMs() {
        if (requestTimingsMs.isEmpty()) {
            return 0;
        }
        return requestTimingsMs.get(0);
    }

    /**
     * Get the delay in milliseconds between request at index i and i+1.
     */
    public long getRequestIntervalMs(int requestIndex) {
        if (requestIndex < 0 || requestIndex >= requestTimingsMs.size() - 1) {
            return 0;
        }
        return requestTimingsMs.get(requestIndex + 1) - requestTimingsMs.get(requestIndex);
    }
}
