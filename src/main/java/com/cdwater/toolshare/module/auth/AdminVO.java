package com.cdwater.toolshare.module.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class AdminVO {

    private Long id;

    private String username;

    /** STAFF / SUPER_ADMIN */
    @Schema(allowableValues = {"STAFF", "SUPER_ADMIN"})
    private String role;
}
