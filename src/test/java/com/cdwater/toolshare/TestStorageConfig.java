package com.cdwater.toolshare;

import com.cdwater.toolshare.shared.storage.StorageService;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 测试用依赖替换：MinIO 换为内存实现，上传/读取测试不依赖对象存储。 */
@TestConfiguration
public class TestStorageConfig {

    @Bean
    @Primary
    StorageService inMemoryStorageService() {
        return new InMemoryStorageService();
    }
}

class InMemoryStorageService extends StorageService {

    private final Map<String, Stored> objects = new ConcurrentHashMap<>();

    InMemoryStorageService() {
        super(null, null);
    }

    @Override
    public FileContent read(String key) {
        Stored stored = objects.get(key);
        if (stored == null) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        return new FileContent(stored.bytes(), stored.contentType());
    }

    @Override
    public String url(String key) {
        return "/api/files/" + key;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, new Stored(bytes.clone(),
                StringUtils.hasText(contentType) ? contentType : "application/octet-stream"));
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    private record Stored(byte[] bytes, String contentType) {
    }
}
