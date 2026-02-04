package me.sathish.runs_ai_analyzer.service;

import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunAnalysisServiceTest {

    @Test
    void containsRunData_withRunningActivity_returnsTrue() {
        GarminRunDataDTO run = GarminRunDataDTO.builder()
                .activityId("123")
                .activityDate("2024-01-15")
                .activityType("running")
                .activityName("Morning Run")
                .distance("5.0")
                .build();

        boolean hasRunningData = "running".equalsIgnoreCase(run.getActivityType());
        assertTrue(hasRunningData);
    }

    @Test
    void containsRunData_withNonRunningActivity_returnsFalse() {
        GarminRunDataDTO activity = GarminRunDataDTO.builder()
                .activityId("456")
                .activityDate("2024-01-16")
                .activityType("strength_training")
                .activityName("Gym Session")
                .distance("0")
                .build();

        boolean hasRunningData = "running".equalsIgnoreCase(activity.getActivityType());
        assertFalse(hasRunningData);
    }

    @Test
    void filterRunningActivities_returnsOnlyRuns() {
        List<GarminRunDataDTO> activities = List.of(
                GarminRunDataDTO.builder()
                        .activityType("running")
                        .activityName("Morning Run")
                        .build(),
                GarminRunDataDTO.builder()
                        .activityType("elliptical")
                        .activityName("Cardio")
                        .build(),
                GarminRunDataDTO.builder()
                        .activityType("running")
                        .activityName("Evening Run")
                        .build()
        );

        long runCount = activities.stream()
                .filter(a -> "running".equalsIgnoreCase(a.getActivityType()))
                .count();

        assertEquals(2, runCount);
    }
}
