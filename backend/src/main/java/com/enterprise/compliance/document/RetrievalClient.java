package com.enterprise.compliance.document;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.enterprise.compliance.common.BusinessException;
import org.springframework.http.HttpStatus;

@Component
public class RetrievalClient {
    public static final String MODEL="BAAI/bge-small-zh-v1.5";
    private final ObjectMapper json;
    private final String url,secret;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    public RetrievalClient(ObjectMapper json,@Value("${app.retrieval.url:http://127.0.0.1:8091}") String url,@Value("${app.retrieval.secret:}") String secret) {
        this.json=json; this.url=url; this.secret=secret;
    }
    public JsonNode call(String path,Object body) {
        if(secret.length()<32) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"检索服务尚未配置");
        try {
            var request=HttpRequest.newBuilder(URI.create(url+"/internal/"+path)).timeout(Duration.ofSeconds(180))
                .header("Content-Type","application/json").header("X-Retrieval-Secret",secret)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var reply=http.send(request,info->new ParserClient.LimitedBody());
            if(reply.statusCode()!=200 || reply.body().length>1024*1024) throw new IllegalStateException();
            var value=json.readTree(reply.body());
            if(!MODEL.equals(value.path("model").asText())) throw new IllegalStateException();
            return value;
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch(Exception ex) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"向量服务繁忙、超时或返回无效结果，请稍后重试"); }
}
