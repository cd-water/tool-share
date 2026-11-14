package com.cdwater.toolshare.module.rental;

import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface RentalVoMapper {

    @Mapping(target = "toolImageUrl", ignore = true)
    @Mapping(target = "renterPhone", ignore = true)
    @Mapping(target = "depositStatusName", ignore = true)
    @Mapping(target = "statusName", ignore = true)
    @Mapping(target = "damageResultName", ignore = true)
    @Mapping(target = "remindBeforeMinutes", ignore = true)
    @Mapping(target = "remindAt", ignore = true)
    RentalVO toVO(Rental rental);

    @AfterMapping
    default void enrich(Rental rental, @MappingTarget RentalVO vo) {
        vo.setRenterPhone(IdCardCipher.maskPhone(rental.getRenterPhone()));
        vo.setDepositStatusName(Dict.label(Dict.DEPOSIT_STATUS, rental.getDepositStatus()));
        vo.setStatusName(Dict.label(Dict.RENTAL_STATUS, rental.getStatus()));
        vo.setDamageResultName(Dict.label(Dict.DAMAGE_RESULT, rental.getDamageResult()));
    }
}
