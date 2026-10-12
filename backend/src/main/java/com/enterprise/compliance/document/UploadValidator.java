package com.enterprise.compliance.document;

import com.enterprise.compliance.common.BusinessException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class UploadValidator {
    public record Validated(String filename, String contentType, byte[] bytes, String sha256) {}
    public Validated validate(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw invalid("文件必须非空且不超过 10 MiB");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank() || filename.length() > 255 || filename.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("文件名无效");
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try {
            byte[] bytes;
            try (var input = file.getInputStream()) { bytes = input.readNBytes(10 * 1024 * 1024 + 1); }
            if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw invalid("文件必须非空且不超过 10 MiB");
            String type = switch (ext) {
                case "pdf" -> {
                    String header = new String(bytes, 0, Math.min(bytes.length, 8), StandardCharsets.US_ASCII);
                    if (!header.matches("%PDF-[12]\\.[0-9].*")) throw invalid("PDF 文件签名无效");
                    yield "application/pdf";
                }
                case "docx" -> { validateDocx(bytes); yield "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
                case "png", "jpg", "jpeg" -> {
                    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                        var readers = ImageIO.getImageReaders(input);
                        if (!readers.hasNext()) throw invalid("图片内容无效");
                        var reader = readers.next();
                        try {
                            reader.setInput(input);
                            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                            if (!(ext.equals("png") ? format.equals("png") : format.equals("jpeg"))) throw invalid("扩展名与图片内容不一致");
                            if ((long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000) throw invalid("图片像素总数不能超过 4000 万");
                            if (reader.read(0) == null) throw invalid("图片无法读取");
                        } finally { reader.dispose(); }
                    }
                    yield ext.equals("png") ? "image/png" : "image/jpeg";
                }
                default -> throw invalid("仅支持 PDF、DOCX、PNG、JPG 和 JPEG");
            };
            return new Validated(filename, type, bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid("文件内容损坏或不符合格式要求"); }
    }
    private void validateDocx(byte[] bytes) throws Exception {
        Map<String, byte[]> required = new HashMap<>();
        Set<String> names = new HashSet<>();
        long expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (!names.add(name) || names.size() > 1000 || name.contains("..") || name.startsWith("/") || name.contains("\\") || name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) throw invalid("Word 压缩包内容不符合要求");
                var content = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                boolean needed = Set.of("[Content_Types].xml", "word/document.xml").contains(name);
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 30 * 1024 * 1024) throw invalid("Word 解压后内容超过限制");
                    if (needed) content.write(buffer, 0, count);
                }
                if (needed) required.put(name, content.toByteArray());
            }
        }
        if (required.size() != 2) throw invalid("不是有效的 DOCX 文件");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        var document = builder.parse(new ByteArrayInputStream(required.get("word/document.xml")));
        if (!"document".equals(document.getDocumentElement().getLocalName()) || !"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(document.getDocumentElement().getNamespaceURI())) throw invalid("Word 正文结构无效");
        var types = builder.parse(new ByteArrayInputStream(required.get("[Content_Types].xml")));
        if (!"Types".equals(types.getDocumentElement().getLocalName()) || !new String(required.get("[Content_Types].xml"), StandardCharsets.UTF_8).contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")) throw invalid("Word 类型声明无效");
    }
    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
}
