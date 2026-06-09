package com.aicall.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class IntentCustomerVO {
    private Integer id;
    private String phone;
    private String name;
    private String level;
    private LocalDateTime lastCallTime;
    private String customerNeed;
    private String customerPain;
    private String budget;
    private String nextTime;
}
