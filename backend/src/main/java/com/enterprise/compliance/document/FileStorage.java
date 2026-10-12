package com.enterprise.compliance.document;

public interface FileStorage {
    String store(byte[] content);
    byte[] read(String key);
    void remove(String key);
}
