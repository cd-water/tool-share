package com.cdwater.toolshare.file.service;

import com.cdwater.toolshare.file.vo.FileVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文件业务接口
 */
public interface FileService {

    FileVO upload(MultipartFile file);
}
