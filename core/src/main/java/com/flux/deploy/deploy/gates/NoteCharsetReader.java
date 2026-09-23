package com.flux.deploy.deploy.gates;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 版本记录（note）文件的宽容读取器。
 *
 * <p>客户 FTP 上历史遗留的版本记录文件未必是 UTF-8（中文 Windows 环境常见 GBK/GB2312），
 * 直接用 UTF-8 严格解码会抛 {@link CharacterCodingException}（{@code Input length = 1}），
 * 导致整个「说明」阶段失败。本类按以下顺序解码，保证<b>绝不抛字符集异常</b>：</p>
 * <ol>
 *   <li>UTF-8 严格解码——正常文件走这条，无日志；</li>
 *   <li>失败则用 GB18030 严格解码（GBK/GB2312 的国标超集），成功时输出一条提示日志；</li>
 *   <li>仍失败（极罕见）则用 UTF-8 宽容解码（非法字节替换为 {@code \\uFFFD}），输出告警日志。</li>
 * </ol>
 *
 * <p>读出的内容随后由调用方统一以 UTF-8 写回，即所有版本记录文件最终收敛为 UTF-8 标准编码。</p>
 *
 * @author xumanyi
 * @date 2026-06-02
 */
public final class NoteCharsetReader {

    /** GBK/GB2312 的国标超集，作为非 UTF-8 中文文件的回退编码。 */
    private static final Charset GB18030 = Charset.forName("GB18030");

    private NoteCharsetReader() {
    }

    /**
     * 宽容读取本地 note 临时文件并返回文本内容，绝不因编码非法而抛异常。
     *
     * @param file        本地待解码文件
     * @param displayName 用于日志的文件名（如远端文件名）
     * @param log         日志回调，可为 null；仅在文件非 UTF-8 时才输出一条说明
     * @return 解码后的文本（内部为 UTF-16，调用方写回时统一转 UTF-8）
     * @throws IOException 读取文件字节失败
     * @author xumanyi
     * @date 2026-06-02
     */
    public static String readLenient(Path file, String displayName, Consumer<String> log)
            throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // 1. 首选 UTF-8 严格解码——正常文件走这条，不打扰用户
        String utf8 = tryStrictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        // 2. 回退 GB18030（GBK/GB2312 国标超集），覆盖中文 Windows 环境下的历史文件
        String gb18030 = tryStrictDecode(bytes, GB18030);
        if (gb18030 != null) {
            if (log != null) {
                log.accept("[说明] " + displayName
                        + " 检测为非 UTF-8 编码（按 GB18030 解码），写回时统一转为 UTF-8");
            }
            return gb18030;
        }
        // 3. 兜底：UTF-8 宽容解码，非法字节替换为 �，保证「说明」阶段绝不中断
        if (log != null) {
            log.accept("[说明] " + displayName
                    + " 编码无法识别，已按 UTF-8 宽容解码（非法字节以替换字符代替），写回时转为 UTF-8");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 用指定编码严格解码（非法/不可映射字节即报错），失败返回 null 以便调用方尝试下一种编码。
     *
     * @param bytes   原始字节
     * @param charset 目标编码
     * @return 解码成功的文本；该编码无法严格解码时返回 null
     * @author xumanyi
     * @date 2026-06-02
     */
    private static String tryStrictDecode(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
