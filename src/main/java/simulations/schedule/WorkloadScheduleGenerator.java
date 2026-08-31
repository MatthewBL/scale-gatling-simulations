package simulations.schedule;

import simulations.UserAssignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates request schedules for all users before the simulation starts.
 * Takes into account usage profiles, looseness variation, and workload distribution strategy.
 * 
 * This modular design allows for easy extension with different workload trends in the future.
 */
public class WorkloadScheduleGenerator {
    private final Random random;
    private final WorkloadTrendStrategy trendStrategy;
    private final int looseness; // Percentage variation (0-100)
    private final long simulationDurationMs;

    /**
     * Create a workload schedule generator.
     *
     * @param random              Random number generator for reproducibility
     * @param trendStrategy       Strategy for distributing requests over time
     * @param looseness           Percentage variation in request counts (0-100)
     * @param simulationMinutes   Duration of the simulation in minutes
     */
    public WorkloadScheduleGenerator(
            Random random,
            WorkloadTrendStrategy trendStrategy,
            int looseness,
            int simulationMinutes) {
        this.random = random;
        this.trendStrategy = trendStrategy;
        this.looseness = Math.max(0, Math.min(100, looseness)); // Clamp to [0, 100]
        this.simulationDurationMs = (long) simulationMinutes * 60_000;
    }

    /**
     * Generate schedules for all users based on their assignments.
     *
     * @param userAssignments list of user assignments with their tiers and usage profiles
     * @return list of schedules, one per user
     */
    public List<UserWorkloadSchedule> generateSchedules(List<UserAssignment> userAssignments) {
        List<UserWorkloadSchedule> schedules = new ArrayList<>(userAssignments.size());

        for (UserAssignment assignment : userAssignments) {
            schedules.add(generateScheduleForUser(assignment));
        }

        return schedules;
    }

    /**
     * Generate a schedule for a single user.
     *
     * @param assignment the user assignment containing tier and usage profile
     * @return the generated schedule
     */
    private UserWorkloadSchedule generateScheduleForUser(UserAssignment assignment) {
        // Calculate target requests with looseness variation
        int baseTargetRequests = calculateBaseTargetRequests(assignment.getHourlyRequests());
        int targetRequests = applyLooseness(baseTargetRequests);

        UserWorkloadSchedule schedule = new UserWorkloadSchedule(assignment.getIndex(), targetRequests);

        // Generate request timings
        for (int i = 0; i < targetRequests; i++) {
            long timeMs = trendStrategy.getRequestTimeMs(i, targetRequests, simulationDurationMs);
            schedule.addRequestTiming(timeMs);
        }

        return schedule;
    }

    /**
     * Calculate the base number of requests based on hourly rate and simulation duration.
     */
    private int calculateBaseTargetRequests(double hourlyRequests) {
        if (hourlyRequests <= 0) return 0;
        // Convert hourly requests to simulation period requests
        double simulationHours = simulationDurationMs / (60_000.0 * 60);
        return Math.max(1, (int) Math.round(hourlyRequests * simulationHours));
    }

    /**
     * Apply looseness variation to a target request count.
     * Looseness is a percentage that determines the range of variation.
     *
     * @param baseCount base number of requests
     * @return adjusted number of requests with random variation
     */
    private int applyLooseness(int baseCount) {
        if (looseness == 0 || baseCount <= 0) {
            return baseCount;
        }

        // Calculate the range: base ± (base * looseness / 100)
        int variation = Math.max(1, (int) Math.round(baseCount * looseness / 100.0));
        int min = Math.max(0, baseCount - variation);
        int max = baseCount + variation;

        // Return a random value within the range
        return min + random.nextInt(max - min + 1);
    }
}
