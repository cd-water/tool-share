package com.cdwater.toolshare.rental.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 租赁记录导出 Excel 行（枚举已转为中文，时间已格式化）
 */
@Data
public class RentalExportVO {

    @ExcelProperty("预约单号")
    private String orderNo;

    @ExcelProperty("工具编号")
    private String toolCode;

    @ExcelProperty("工具名称")
    private String toolName;

    @ExcelProperty("租赁人")
    private String userName;

    @ExcelProperty("联系方式")
    private String userPhone;

    @ExcelProperty("取件人")
    private String pickupName;

    @ExcelProperty("租赁开始时间")
    private String startTime;

    @ExcelProperty("租赁结束时间")
    private String endTime;

    @ExcelProperty("押金金额(元)")
    private BigDecimal depositAmount;

    @ExcelProperty("押金状态")
    private String depositStatusText;

    @ExcelProperty("状态")
    private String statusText;

    @ExcelProperty("验收结果")
    private String acceptResultText;

    @ExcelProperty("逾期费用(元)")
    private BigDecimal overdueFee;

    @ExcelProperty("押金实退(元)")
    private BigDecimal depositRefundAmount;

    @ExcelProperty("预约时间")
    private String createTime;
}
