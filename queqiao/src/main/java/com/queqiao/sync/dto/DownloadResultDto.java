package com.queqiao.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResultDto {
    private Long inspectId;
    private String fileName;
    private String docxPath;
}
