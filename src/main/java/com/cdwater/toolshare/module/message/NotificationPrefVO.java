package com.cdwater.toolshare.module.message;

import lombok.Data;

@Data
public class NotificationPrefVO {

    private String type;

    private String typeName;

    /** SYSTEM/SMS */
    private String channel;

    private Boolean enabled;
}
