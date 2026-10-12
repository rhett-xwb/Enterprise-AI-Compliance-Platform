package com.enterprise.compliance.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.parser.enabled",havingValue="true")
@ConditionalOnExpression("!${app.async.enabled:false}")
public class ParsingWorker {
    private final ParsingStore store;
    private final ParserClient client;
    public ParsingWorker(ParsingStore store,ParserClient client) { this.store=store; this.client=client; }
    @Scheduled(fixedDelayString="${app.parser.poll-ms:1000}")
    public void poll() {
        var work=store.claim(); if(work==null) return;
        try { store.complete(work,client.parse(work)); }
        catch(ParserClient.Failure ex) { store.fail(work,ex.code); }
        catch(Exception ex) { store.fail(work,"PARSER_RESPONSE_INVALID"); }
    }
}
