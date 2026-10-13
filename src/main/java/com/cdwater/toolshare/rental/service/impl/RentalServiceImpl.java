package com.cdwater.toolshare.rental.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.rental.dto.RentalCreateDTO;
import com.cdwater.toolshare.rental.dto.RentalPickupDTO;
import com.cdwater.toolshare.rental.dto.RentalQueryDTO;
import com.cdwater.toolshare.rental.dto.RentalReturnDTO;
import com.cdwater.toolshare.rental.service.RentalService;
import com.cdwater.toolshare.rental.vo.RentalCreateVO;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import com.cdwater.toolshare.rental.vo.TimeSlotVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 租赁业务实现（空实现，待填充）
 */
@Service
public class RentalServiceImpl implements RentalService {

    @Override
    public List<TimeSlotVO> availableTimes(Long toolId, LocalDate date) {
        return null;
    }

    @Override
    public RentalCreateVO create(RentalCreateDTO dto) {
        return null;
    }

    @Override
    public void pay(Long id) {
    }

    @Override
    public void cancel(Long id) {
    }

    @Override
    public void pickup(Long id, RentalPickupDTO dto) {
    }

    @Override
    public void returnTool(Long id, RentalReturnDTO dto) {
    }

    @Override
    public PageResult<RentalOrderVO> page(RentalQueryDTO query) {
        return null;
    }

    @Override
    public RentalOrderVO detail(Long id) {
        return null;
    }

    @Override
    public void export(RentalQueryDTO query) {
    }
}
