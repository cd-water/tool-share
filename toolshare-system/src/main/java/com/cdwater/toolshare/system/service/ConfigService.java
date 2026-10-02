package com.cdwater.toolshare.system.service;

import com.cdwater.toolshare.system.dto.ConfigItem;
import java.util.List;

/**
 * 全局配置服务（ADMIN）
 */
public interface ConfigService {

    List<ConfigItem> list();

    void update(String key, String configValue);
}
