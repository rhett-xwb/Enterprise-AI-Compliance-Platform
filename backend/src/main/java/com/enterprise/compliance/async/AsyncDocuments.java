package com.enterprise.compliance.async;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.document.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AsyncDocuments {
    private final AsyncJobs jobs;
    private final ParsingStore parsing;
    private final IndexStore index;
    private final DocumentService docs;
    private final JdbcTemplate jdbc;
    public AsyncDocuments(AsyncJobs jobs,ParsingStore parsing,IndexStore index,DocumentService docs,JdbcTemplate jdbc) { this.jobs=jobs; this.parsing=parsing; this.index=index; this.docs=docs; this.jdbc=jdbc; }
    @Transactional
    public ParsingStore.Task parse(long id,long version,PlatformPrincipal user,boolean retry) {
        docs.requireManaged(id,user);
        var task=parsing.enqueue(id,version,user,retry);
        if(task.status().equals("PENDING")) jobs.enqueue(id,version,"PARSE","parse:"+version,java.util.Map.of("taskId",task.id()));
        return task;
    }
    @Transactional
    public IndexStore.State index(long id,long version,PlatformPrincipal user,boolean rebuild) {
        docs.requireManaged(id,user);
        // Serialize preparation, so parallel requests never generate two sets of vector keys.
        jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE",id);
        if(jobs.active(version,"INDEX")) return index.state(id,version,user);
        var failed=jobs.failedIndex(version);
        if(failed!=null) { jobs.retry(failed); return index.state(id,version,user); }
        var work=index.prepare(id,version,user,rebuild);
        if(work!=null) jobs.enqueue(id,version,"INDEX","index:"+version+":"+work.generation(),work);
        return index.state(id,version,user);
    }
}
