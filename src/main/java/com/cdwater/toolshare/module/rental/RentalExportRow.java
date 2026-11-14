/**
 * 租赁单 Excel 导出行文件。
 * <p>字段全部为展示字符串，由 RentalService 从租赁单快照转换。
 * 表头通过 EasyExcel 的 @ExcelProperty 固定，字段顺序即列顺序。
 * <h2>隐私</h2>
 * <p>手机号在导出时再次脱敏，身份证直接使用入库时的脱敏快照；
 * 导出文件可以安全流转，不包含完整证件信息。
  * <h2>列顺序</h2>
  * <p>字段声明顺序即 Excel 列顺序：单号、工具、租用人、联系方式、证件、
  * 取件人、录入/更新人、租期、押金、状态、实际归还、验收结果、费用与退款。
  * 调整列顺序会影响下游报表，修改前需确认使用方。
  * <p>所有字段都是 String，便于统一处理空值；数值与时间在服务层格式化。
 * <h2>金额与时间</h2>
 * <p>金额使用 toPlainString 避免科学计数法；时间统一为 yyyy-MM-dd HH:mm，
 * 空值输出空串。
 * <p>调用方：AdminRentalController#export。
 */
package com.cdwater.toolshare.module.rental;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 租赁单 Excel 导出行。
 * <p>字段全部为展示字符串，由 RentalService 从租赁单快照转换；
 * 表头通过 EasyExcel 的 {@code @ExcelProperty} 固定，字段顺序即列顺序。
 * 手机号与身份证在转换阶段已脱敏，导出文件可安全流转。
 */
@Data
public class RentalExportRow {

    // 租赁单号：对外唯一标识
    // 第一列：租赁单号
    @ExcelProperty("租赁单号")
    private String orderNo;

    // 工具业务编号
    // 第二列：工具编号
    @ExcelProperty("工具编号")
    private String toolCode;

    // 工具名称
    // 第三列：工具名称
    @ExcelProperty("工具名称")
    private String toolName;

    // 租赁人姓名
    // 第四列：租用人姓名
    @ExcelProperty("租赁人")
    private String renterName;

    // 脱敏手机号，中间四位以星号代替
    // 第五列：脱敏手机号
    @ExcelProperty("联系方式")
    private String renterPhone;

    // 身份证脱敏快照，不包含完整证件号
    // 第六列：脱敏身份证
    @ExcelProperty("身份证（脱敏）")
    private String renterIdCardMasked;

    // 实际取件人，可能不是租赁人
    // 第七列：实际取件人
    @ExcelProperty("取件人")
    private String pickupPerson;

    // 创建租赁单的经办人
    // 第八列：创建经办人
    @ExcelProperty("录入人")
    private String createdByName;

    // 最近一次归还/取消的经办人
    // 第九列：归还/取消经办人
    @ExcelProperty("更新人")
    private String updatedByName;

    // 租期开始时间，yyyy-MM-dd HH:mm
    // 第十列：租期开始
    @ExcelProperty("租赁开始")
    private String startTime;

    // 租期结束时间
    // 第十一列：租期结束
    @ExcelProperty("租赁结束")
    private String endTime;

    // 押金金额快照
    // 第十二列：押金
    @ExcelProperty("押金")
    private String depositAmount;

    // 租赁状态中文名
    // 第十三列：租赁状态中文名
    @ExcelProperty("状态")
    private String statusName;

    // 押金状态中文名
    // 第十四列：押金状态中文名
    @ExcelProperty("押金状态")
    private String depositStatusName;

    // 实际归还时间；未归还时为空
    // 第十五列：实际归还时间
    @ExcelProperty("实际归还")
    private String actualReturnTime;

    // 验收结果中文名
    // 第十六列：验收结果中文名
    @ExcelProperty("验收结果")
    private String damageResultName;

    // 逾期费原始金额
    // 第十七列：逾期费
    @ExcelProperty("逾期费")
    private String overdueFee;

    // 损坏赔偿原始金额
    // 第十八列：损坏赔偿
    @ExcelProperty("损坏赔偿")
    private String damageFee;

    // 押金退还金额
    // 第十九列：押金退款
    @ExcelProperty("退还金额")
    private String refundAmount;
}
