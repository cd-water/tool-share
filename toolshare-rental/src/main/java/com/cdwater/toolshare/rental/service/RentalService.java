package com.cdwater.toolshare.rental.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.rental.dto.PayResponse;
import com.cdwater.toolshare.rental.dto.PickupRequest;
import com.cdwater.toolshare.rental.dto.RentalCreateRequest;
import com.cdwater.toolshare.rental.dto.RentalCreatedResponse;
import com.cdwater.toolshare.rental.dto.RentalOrderItem;
import com.cdwater.toolshare.rental.dto.RentalQuery;
import com.cdwater.toolshare.rental.dto.ReturnRequest;
import com.cdwater.toolshare.rental.dto.SettleResponse;
import com.cdwater.toolshare.rental.dto.SuggestionResponse;
import java.time.LocalDateTime;

/**
 * 预约 / 押金 / 归还结算 / 租赁记录（含延迟提醒投递）
 */
public interface RentalService {

    RentalCreatedResponse create(RentalCreateRequest request);

    SuggestionResponse suggestion(Long toolId, LocalDateTime startTime, LocalDateTime endTime);

    PayResponse pay(Long id);

    void cancel(Long id);

    void pickup(Long id, PickupRequest request);

    SettleResponse returnOrder(Long id, ReturnRequest request);

    PageResult<RentalOrderItem> my(long page, long size, String status);

    PageResult<RentalOrderItem> page(RentalQuery query);

    byte[] export(RentalQuery query);
}
