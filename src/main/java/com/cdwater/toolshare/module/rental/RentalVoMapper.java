/**
 * 租赁实体到视图的 MapStruct 映射文件。
 * <p>手机号在后置处理中脱敏；押金状态、租赁状态与验收结果翻译为中文名。
  * <h2>映射细节</h2>
  * <p>renterPhone 在后置处理中脱敏；押金状态、租赁状态与验收结果通过 Dict 翻译。
  * 工具主图、提醒字段、费用与流水依赖其他表或 Redis，由 RentalService 补齐。
  * <p>映射器不做所有权校验与状态判断，保持纯转换。
 * <h2>职责边界</h2>
 * <p>工具主图、提醒字段、费用流水与资金流水依赖其他表或 Redis，
 * 由 RentalService 批量补齐，映射器保持无外部访问。
 * <p>调用方：RentalService#toVO/toVOList。
 */
package com.cdwater.toolshare.module.rental;

import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * 租赁实体到视图的 MapStruct 映射。
 * <p>手机号在后置处理中脱敏；状态、押金状态与验收结果翻译为中文名。
 * 工具主图、提醒字段与费用流水由 RentalService 补齐。
 */
@Mapper(componentModel = "spring")
public interface RentalVoMapper {

    @Mapping(target = "toolImageUrl", ignore = true)
    @Mapping(target = "renterPhone", ignore = true)
    @Mapping(target = "depositStatusName", ignore = true)
    @Mapping(target = "statusName", ignore = true)
    @Mapping(target = "damageResultName", ignore = true)
    @Mapping(target = "remindBeforeMinutes", ignore = true)
    @Mapping(target = "remindAt", ignore = true)
    /**
     * 基础字段映射。
     *
     * @param rental 租赁单实体
     * @return 租赁视图，展示名称字段待后置处理
     */
    RentalVO toVO(Rental rental);

    // 后置映射：手机号脱敏 + 字典编码翻译，保证对外视图不泄露完整号码
    @AfterMapping
    default void enrich(Rental rental, @MappingTarget RentalVO vo) {
        // 手机号脱敏后再对外展示
        vo.setRenterPhone(IdCardCipher.maskPhone(rental.getRenterPhone()));
        // 押金状态翻译
        vo.setDepositStatusName(Dict.label(Dict.DEPOSIT_STATUS, rental.getDepositStatus()));
        // 租赁状态翻译
        vo.setStatusName(Dict.label(Dict.RENTAL_STATUS, rental.getStatus()));
        // 验收结果翻译
        vo.setDamageResultName(Dict.label(Dict.DAMAGE_RESULT, rental.getDamageResult()));
    }
}
