package com.cdwater.toolshare.module.auth;

import lombok.Data;

@Data
public class AdminVO {

    private Long id;

    private String username;

    /** STAFF / SUPER_ADMIN */
    private String role;
}
