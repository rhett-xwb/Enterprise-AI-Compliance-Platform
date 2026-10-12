package com.enterprise.compliance.async;

import com.enterprise.compliance.document.ParsingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Cache immutable extraction only. Callers MUST authorize against MySQL before each read. */
@Component
public class SourceCache {
    private final boolean enabled; private final String namespace; private final StringRedisTemplate redis; private final ObjectMapper json;
    private final LongAdder hits=new LongAdder(),misses=new LongAdder(),errors=new LongAdder();
    public SourceCache(@Value("${app.cache.enabled:false}") boolean enabled,@Value("${app.cache.namespace:compliance}") String namespace,StringRedisTemplate redis,ObjectMapper json) {
        this.enabled=enabled; this.namespace=namespace; this.redis=redis; this.json=json;
    }
    public List<ParsingStore.Source> get(long version,String hash) {
        if(!enabled) return null;
        try {
            var raw=redis.opsForValue().get(namespace+":sources:v1:"+version+":"+hash);
            if(raw==null) { misses.increment(); return null; }
            var sources=json.<List<ParsingStore.Source>>readValue(raw,json.getTypeFactory().constructCollectionType(List.class,ParsingStore.Source.class));
            hits.increment(); return sources;
        } catch(Exception ex) { errors.increment(); return null; }
    }
    public void put(long version,String hash,List<ParsingStore.Source> sources) {
        if(!enabled || sources.isEmpty()) return;
        try {
            var raw=json.writeValueAsString(sources);
            // Large OCR bodies stay in MySQL. Short TTL bounds memory and orphan retention.
            if(raw.length()<=256000) redis.opsForValue().set(namespace+":sources:v1:"+version+":"+hash,raw,java.util.Objects.requireNonNull(Duration.ofMinutes(5)));
        } catch(Exception ex) { errors.increment(); }
    }
    public java.util.Map<String,Object> metrics() { return java.util.Map.of("enabled",enabled,"hits",hits.sum(),"misses",misses.sum(),"errors",errors.sum()); }
}
