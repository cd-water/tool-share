package com.cdwater.toolshare.system.dto;

import lombok.Data;

@Data
public class LoginResponse {

    private String token;

    private UserItem user;
}
