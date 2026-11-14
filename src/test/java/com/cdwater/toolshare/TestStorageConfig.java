package com.cdwater.toolshare;

import com.cdwater.toolshare.shared.storage.FileVO;
import com.cdwater.toolshare.shared.storage.StorageService;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
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
    public FileVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择文件");
        }
        String original = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "file";
        String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String key = UUID.randomUUID().toString().replace("-", "") + "-" + safeName;
        try {
            put(key, file.getBytes(), file.getContentType());
        } catch (IOException e) {
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "文件上传失败");
        }
        return new FileVO(url(key), original);
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
