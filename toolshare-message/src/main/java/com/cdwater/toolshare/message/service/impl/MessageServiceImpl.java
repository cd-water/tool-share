package com.cdwater.toolshare.message.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.message.dto.MsgItem;
import com.cdwater.toolshare.message.service.MessageService;
import org.springframework.stereotype.Service;

@Service
public class MessageServiceImpl implements MessageService {

    @Override
    public PageResult<MsgItem> my(long page, long size, String type) {
        // TODO 业务实现：StpUtil.getLoginIdAsLong() 过滤接收人
        return null;
    }

    @Override
    public PageResult<MsgItem> page(long page, long size, Long userId, String type) {
        // TODO 业务实现：全量存档审计
        return null;
    }
}
