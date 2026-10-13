package com.cdwater.toolshare.file.service.impl;

import com.cdwater.toolshare.file.service.FileService;
import com.cdwater.toolshare.file.vo.FileVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文件业务实现（空实现，待填充）
 */
@Service
public class FileServiceImpl implements FileService {

    @Override
    public FileVO upload(MultipartFile file) {
        return null;
    }
}
