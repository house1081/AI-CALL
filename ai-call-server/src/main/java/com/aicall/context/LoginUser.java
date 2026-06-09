package com.aicall.context;

import lombok.Data;

@Data
public class LoginUser {
    private Integer userId;
    private String username;
    /** admin | tenant */
    private String role;
    private Integer tenantId;
}
