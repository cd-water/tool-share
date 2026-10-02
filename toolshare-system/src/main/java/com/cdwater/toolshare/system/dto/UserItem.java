package com.cdwater.toolshare.system.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserItem {

    private Long id;

    private String username;

    private String realName;

    private String phone;

    private String role;

    private String status;

    private LocalDateTime createTime;
}
