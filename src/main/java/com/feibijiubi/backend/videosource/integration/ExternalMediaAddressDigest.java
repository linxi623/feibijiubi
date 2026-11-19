package com.feibijiubi.backend.videosource.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 媒体地址摘要的唯一实现：S2 抓取写入 {@code address_hash} 与 M6 播放校验读取它时必须使用同一算法，
 * 否则同一地址会在两处得到不同摘要。
 *
 * <p>只计算 {@code SHA-256(UTF-8 原始字符串)} 的小写十六进制；<b>不</b> trim、<b>不</b>规范化、不解析
 * URL，因此不会改变签名地址的原始字节。异常文案不含地址。</p>
 */
public final class ExternalMediaAddressDigest {

    private ExternalMediaAddressDigest() {
    }

    public static String sha256Hex(String address) {
        if (address == null) {
            throw new IllegalArgumentException("媒体地址不能为空");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
