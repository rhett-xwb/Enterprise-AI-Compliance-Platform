package com.enterprise.compliance.async;

import com.enterprise.compliance.document.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AsyncProcessor {
    private final AsyncJobs jobs; private final ParsingStore parsing; private final ParserClient parser;
    private final IndexStore index; private final RetrievalClient retrieval; private final ObjectMapper json;
    public AsyncProcessor(AsyncJobs jobs,ParsingStore parsing,ParserClient parser,IndexStore index,RetrievalClient retrieval,ObjectMapper json) { this.jobs=jobs; this.parsing=parsing; this.parser=parser; this.index=index; this.retrieval=retrieval; this.json=json; }
    public void process(AsyncJobs.Message message) {
        var job=jobs.claim(message); if(job==null) return;
        String error=null;
        if(job.type().equals("PARSE")) {
            ParsingStore.Work work=null;
            try {
                work=parsing.claim(json.readTree(job.payload()).path("taskId").asLong());
                if(work==null) error="RESULT_NOT_COMMITTED";
                else parsing.complete(work,parser.parse(work));
            } catch(ParserClient.Failure ex) { error=ex.code; if(work!=null) parsing.fail(work,error); }
            catch(Exception ex) { error="PARSER_RESPONSE_INVALID"; if(work!=null) parsing.fail(work,error); }
        } else {
            IndexStore.Work work=null;
            try {
                work=json.readValue(job.payload(),IndexStore.Work.class);
                index.complete(work,retrieval.call("index",Map.of("documentId",work.documentId(),"documentVersionId",work.documentVersionId(),"generation",work.generation(),"roles",work.roles(),"departmentIds",work.departmentIds(),"chunks",work.chunks())));
            } catch(Exception ex) { error="INDEX_UNAVAILABLE"; if(work!=null) index.fail(work,error); }
        }
        // A listener only ACKs after this durable transition. Crashes are recovered by lease.
        jobs.finished(job,error);
    }
}
