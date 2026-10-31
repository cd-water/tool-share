package com.cdwater.toolshare.module.rental;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class RentalExportRow {

    @ExcelProperty("租赁预约单号")
    private String orderNo;

    @ExcelProperty("工具编号")
    private String toolCode;

    @ExcelProperty("工具名称")
    private String toolName;

    @ExcelProperty("租赁人")
    private String renterName;

    @ExcelProperty("联系方式")
    private String renterPhone;

    @ExcelProperty("取件人")
    private String pickupPerson;

    @ExcelProperty("租赁开始")
    private String startTime;

    @ExcelProperty("租赁结束")
    private String endTime;

    @ExcelProperty("押金")
    private String depositAmount;

    @ExcelProperty("状态")
    private String statusName;

    @ExcelProperty("押金状态")
    private String depositStatusName;

    @ExcelProperty("实际归还")
    private String actualReturnTime;

    @ExcelProperty("验收结果")
    private String damageResultName;

    @ExcelProperty("逾期费")
    private String overdueFee;

    @ExcelProperty("损坏赔偿")
    private String damageFee;

    @ExcelProperty("退还金额")
    private String refundAmount;
}
