package com.queqiao.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerResultDto {
    private String taskId;
    private boolean accepted;
}
