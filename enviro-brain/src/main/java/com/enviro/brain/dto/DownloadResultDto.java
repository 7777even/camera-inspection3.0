package com.enviro.brain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DownloadResultDto {
    private Long inspectId;
    private String fileName;
    private String docxPath;
}
