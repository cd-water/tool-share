package com.cdwater.toolshare.repair.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.repair.dto.RepairAcceptDTO;
import com.cdwater.toolshare.repair.dto.RepairCreateDTO;
import com.cdwater.toolshare.repair.dto.RepairProgressDTO;
import com.cdwater.toolshare.repair.dto.RepairQueryDTO;
import com.cdwater.toolshare.repair.dto.RepairVerifyDTO;
import com.cdwater.toolshare.repair.vo.RepairCreateVO;
import com.cdwater.toolshare.repair.vo.RepairOrderVO;

/**
 * 损坏报修业务接口
 */
public interface RepairService {

    RepairCreateVO create(RepairCreateDTO dto);

    PageResult<RepairOrderVO> page(RepairQueryDTO query);

    RepairOrderVO detail(Long id);

    void verify(Long id, RepairVerifyDTO dto);

    void progress(Long id, RepairProgressDTO dto);

    void accept(Long id, RepairAcceptDTO dto);
}
