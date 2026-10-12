package com.enterprise.compliance.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ParserClient {
    public static class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
    }
    private final ObjectMapper json;
    private final String url, secret;
    private final int timeout;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;
        private final long limit;
        LimitedBody() { this(12*1024*1024); }
        LimitedBody(long limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            if(failed) return;
            for(var buffer:buffers) bytes+=buffer.remaining();
            if(bytes>limit) { failed=true; subscription.cancel(); delegate.onError(new Failure("PARSER_RESPONSE_INVALID")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if(!failed) delegate.onError(error); }
        public void onComplete() { if(!failed) delegate.onComplete(); }
    }
    public ParserClient(ObjectMapper json,@Value("${app.parser.url:http://127.0.0.1:8090}") String url,
                        @Value("${app.parser.secret:}") String secret,@Value("${app.parser.timeout-seconds:190}") int timeout) {
        this.json=json; this.url=url; this.secret=secret; this.timeout=timeout;
    }
    public ParsingStore.Result parse(ParsingStore.Work w) {
        if(secret.length()<32) throw new Failure("PARSER_NOT_CONFIGURED");
        try {
            var body=Map.of("taskId",w.taskId(),"documentVersionId",w.documentVersionId(),"storageKey",w.storageKey(),"contentSha256",w.contentSha256(),"contentType",w.contentType(),"sizeBytes",w.sizeBytes());
            var request=HttpRequest.newBuilder(URI.create(url+"/internal/parse")).timeout(Duration.ofSeconds(timeout))
                .header("Content-Type","application/json").header("X-Parser-Secret",secret).POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            // Byte-array handler keeps the request timeout active through the whole response.
            var reply=http.send(request,info->new LimitedBody());
            if(reply.body().length>12*1024*1024) throw new Failure("PARSER_RESPONSE_INVALID");
            if(reply.statusCode()!=200) {
                String code=json.readTree(reply.body()).path("detail").path("errorCode").asText();
                if(!Set.of("PARSER_BUSY","PARSE_TIMEOUT","PARSE_FAILED","PARSER_CRASH","FILE_REFERENCE_INVALID","FILE_UNAVAILABLE","FILE_SIZE_LIMIT","FILE_INTEGRITY","PAGE_LIMIT","IMAGE_LIMIT","ARCHIVE_LIMIT","SOURCE_LIMIT","TEXT_LIMIT","EMPTY_DOCUMENT","PDF_ENCRYPTED","UNSUPPORTED_TYPE").contains(code)) code="PARSER_UNAVAILABLE";
                throw new Failure(code);
            }
            return json.readValue(reply.body(),ParsingStore.Result.class);
        } catch(Failure ex) { throw ex; }
        catch(HttpTimeoutException ex) { throw new Failure("PARSE_TIMEOUT"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new Failure("WORKER_INTERRUPTED"); }
        catch(Exception ex) { throw new Failure("PARSER_UNAVAILABLE"); }
    }
}
