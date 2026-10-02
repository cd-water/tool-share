package com.cdwater.toolshare.message.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.message.dto.MsgItem;

/**
 * 提醒存档查询（发送由业务事件/延迟消息触发，见技术方案 §3.5③）
 */
public interface MessageService {

    PageResult<MsgItem> my(long page, long size, String type);

    PageResult<MsgItem> page(long page, long size, Long userId, String type);
}
