package com.cdwater.toolshare.repair.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.repair.dto.RepairAcceptDTO;
import com.cdwater.toolshare.repair.dto.RepairCreateDTO;
import com.cdwater.toolshare.repair.dto.RepairProgressDTO;
import com.cdwater.toolshare.repair.dto.RepairQueryDTO;
import com.cdwater.toolshare.repair.dto.RepairVerifyDTO;
import com.cdwater.toolshare.repair.service.RepairService;
import com.cdwater.toolshare.repair.vo.RepairCreateVO;
import com.cdwater.toolshare.repair.vo.RepairOrderVO;
import org.springframework.stereotype.Service;

/**
 * 损坏报修业务实现（空实现，待填充）
 */
@Service
public class RepairServiceImpl implements RepairService {

    @Override
    public RepairCreateVO create(RepairCreateDTO dto) {
        return null;
    }

    @Override
    public PageResult<RepairOrderVO> page(RepairQueryDTO query) {
        return null;
    }

    @Override
    public RepairOrderVO detail(Long id) {
        return null;
    }

    @Override
    public void verify(Long id, RepairVerifyDTO dto) {
    }

    @Override
    public void progress(Long id, RepairProgressDTO dto) {
    }

    @Override
    public void accept(Long id, RepairAcceptDTO dto) {
    }
}
