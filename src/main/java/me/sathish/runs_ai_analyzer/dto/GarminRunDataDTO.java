package me.sathish.runs_ai_analyzer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GarminRunDataDTO {

    private Long id;

    @NotBlank(message = "Activity ID is required")
    private String activityId;

    @NotBlank(message = "Activity date is required")
    private String activityDate;

    @NotBlank(message = "Activity type is required")
    @Pattern(regexp = "^(running|strength_training|elliptical)$", 
             message = "Activity type must be running, strength_training, or elliptical")
    private String activityType;

    @NotBlank(message = "Activity name is required")
    private String activityName;

    private String activityDescription;

    @Pattern(regexp = "^\\d{2}:\\d{2}:\\d{2}$", message = "Elapsed time must be in HH:MM:SS format")
    private String elapsedTime;

    @NotNull(message = "Distance is required")
    @Pattern(regexp = "^\\d+(\\.\\d+)?$", message = "Distance must be a valid number")
    private String distance;

    @Pattern(regexp = "^\\d+$", message = "Max heart rate must be a valid integer")
    private String maxHeartRate;

    @Pattern(regexp = "^\\d+$", message = "Calories must be a valid integer")
    private String calories;
}
