package com.enterprise.compliance.document;

import com.enterprise.compliance.common.BusinessException;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;
    public LocalFileStorage(@Value("${app.documents.storage-root}") String directory) throws IOException {
        Path configured = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        root = configured.toRealPath();
    }
    private Path target(String key) {
        if (key == null || !key.matches("[a-f0-9-]{36}\\.bin")) throw unavailable();
        Path path = root.resolve(key).normalize();
        if (!path.getParent().equals(root) || Files.isSymbolicLink(path)) throw unavailable();
        return path;
    }
    @Override public String store(byte[] content) {
        String key = UUID.randomUUID() + ".bin";
        Path path = target(key);
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                created = true;
                output.write(content);
            }
            return key;
        }
        catch (IOException ex) {
            if (created) { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
            throw unavailable();
        }
    }
    @Override public byte[] read(String key) {
        try {
            Path path = target(key);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 10 * 1024 * 1024) throw unavailable();
            return Files.readAllBytes(path);
        } catch (IOException ex) { throw unavailable(); }
    }
    @Override public void remove(String key) {
        try { Files.deleteIfExists(target(key)); }
        catch (IOException ex) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂不可用，请联系管理员"); }
}
