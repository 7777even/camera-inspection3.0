package com.enviro.brain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportResult {
    private int imported;
    private int updated;
    private int errors;
    @Builder.Default
    private List<String> errorDetails = new ArrayList<>();
}
