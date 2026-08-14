package com.clouddrive.common;

import java.io.InputStream;

import org.springframework.core.io.InputStreamResource;

/**
 * 已知长度的 InputStreamResource（F4 视频 Range 流式）：
 * InputStreamResource.contentLength() 默认返回 -1，Spring ResourceHttpMessageConverter
 * 无法据此处理 Range 请求；子类返回文件真实 size，使浏览器可对下载端点发 Range 得到 206 分片。
 */
public class KnownLengthInputStreamResource extends InputStreamResource {

    private final long length;

    public KnownLengthInputStreamResource(InputStream in, long length) {
        super(in, "cloud-drive-download");
        this.length = length;
    }

    @Override
    public long contentLength() {
        return length;
    }
}
