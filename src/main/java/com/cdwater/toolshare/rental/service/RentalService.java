package com.cdwater.toolshare.rental.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.rental.dto.RentalCreateDTO;
import com.cdwater.toolshare.rental.dto.RentalPickupDTO;
import com.cdwater.toolshare.rental.dto.RentalQueryDTO;
import com.cdwater.toolshare.rental.dto.RentalReturnDTO;
import com.cdwater.toolshare.rental.vo.RentalCreateVO;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import com.cdwater.toolshare.rental.vo.TimeSlotVO;
import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 租赁业务接口
 */
public interface RentalService {

    List<TimeSlotVO> availableTimes(Long toolId, LocalDate date);

    RentalCreateVO create(RentalCreateDTO dto);

    void pay(Long id);

    void cancel(Long id);

    void pickup(Long id, RentalPickupDTO dto);

    void returnTool(Long id, RentalReturnDTO dto);

    PageResult<RentalOrderVO> page(RentalQueryDTO query);

    RentalOrderVO detail(Long id);

    void export(RentalQueryDTO query, HttpServletResponse response);
}
