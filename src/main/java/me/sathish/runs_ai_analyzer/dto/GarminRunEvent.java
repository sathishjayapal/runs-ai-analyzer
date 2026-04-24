package me.sathish.runs_ai_analyzer.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GarminRunEvent {
    private String eventType;
    private String activityId;
    private String activityName;
    private LocalDateTime activityDate;
    private String distance;
    private String elapsedTime;
    private Long databaseId;
    private String status;
    private String errorMessage;
    private String fileName;
    private String activityType;
    private String maxHeartRate;
    private String calories;
}
