package com.cdwater.toolshare.vo;

import lombok.Data;

@Data
public class UserDashboardVO {

    private long availableToolCount;
    private long pendingPaymentCount;
    private long rentingCount;
    private long unreadCount;
}
