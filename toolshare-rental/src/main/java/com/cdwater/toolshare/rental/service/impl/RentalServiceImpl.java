package com.cdwater.toolshare.rental.service.impl;

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
import com.cdwater.toolshare.rental.service.RentalService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class RentalServiceImpl implements RentalService {

    @Override
    public RentalCreatedResponse create(RentalCreateRequest request) {
        // TODO 业务实现：重叠校验 + Redisson lock:tool:{id} 内落单（PENDING_PAYMENT）
        return null;
    }

    @Override
    public SuggestionResponse suggestion(Long toolId, LocalDateTime startTime, LocalDateTime endTime) {
        // TODO 业务实现：30 分钟步进向后探测当日空档
        return null;
    }

    @Override
    public PayResponse pay(Long id) {
        // TODO 业务实现：模拟支付押金 + 投递取件/到期/逾期三条延迟消息
        return null;
    }

    @Override
    public void cancel(Long id) {
        // TODO 业务实现：取件前 24h 外可取消，退押金
    }

    @Override
    public void pickup(Long id, PickupRequest request) {
        // TODO 业务实现：RESERVED → PICKED_UP，工具转 IN_USE
    }

    @Override
    public SettleResponse returnOrder(Long id, ReturnRequest request) {
        // TODO 业务实现：逾期费计算 + 赔偿 + 退押金 + 工具损坏重估
        return null;
    }

    @Override
    public PageResult<RentalOrderItem> my(long page, long size, String status) {
        // TODO 业务实现：StpUtil.getLoginIdAsLong() 过滤本人
        return null;
    }

    @Override
    public PageResult<RentalOrderItem> page(RentalQuery query) {
        // TODO 业务实现：多条件筛选 + 脱敏
        return null;
    }

    @Override
    public byte[] export(RentalQuery query) {
        // TODO 业务实现：EasyExcel 写 xlsx（表头对齐附录·租赁记录单）
        return null;
    }
}
