package com.cdwater.toolshare.system.service.impl;

import com.cdwater.toolshare.system.dto.ConfigItem;
import com.cdwater.toolshare.system.service.ConfigService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConfigServiceImpl implements ConfigService {

    @Override
    public List<ConfigItem> list() {
        // TODO 业务实现
        return null;
    }

    @Override
    public void update(String key, String configValue) {
        // TODO 业务实现
    }
}
