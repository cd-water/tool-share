package com.cdwater.toolshare.module.dashboard;

import lombok.Data;

@Data
public class AdminDashboardVO {

    private long toolTotal;
    private long availableToolCount;
    private long rentedToolCount;
    private long maintenanceToolCount;
    private long activeRentalCount;
    private long overdueRentalCount;
    private long pendingDamageCount;
    private long userCount;
    private long todayRentalCount;
}
