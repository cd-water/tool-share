package com.cdwater.toolshare.module.dashboard;

import lombok.Data;

@Data
public class UserDashboardVO {

    private long availableToolCount;
    private long pendingPaymentCount;
    private long rentingCount;
    private long unreadCount;
}
