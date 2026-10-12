package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionStore {
    public record Citation(int id,RetrievalStore.Hit source,String state) {}
    public record Detail(long id,String question,String status,String answer,List<Citation> citations,String model,
            String embeddingModel,long elapsedMs,String errorCode,LocalDateTime createdAt) {}
    public record Summary(long id,String question,String status,LocalDateTime createdAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RetrievalStore retrieval;
    public QuestionStore(JdbcTemplate jdbc,ObjectMapper json,RetrievalStore retrieval) { this.jdbc=jdbc; this.json=json; this.retrieval=retrieval; }
    @Transactional
    public long save(PlatformPrincipal user,String question,String status,String answer,List<RetrievalStore.Hit> evidence,List<Integer> ids,String model,long elapsed,String error) {
        try {
            var key=new GeneratedKeyHolder();
            var values=new Object[]{user.id(),question,status,answer,json.writeValueAsString(evidence),json.writeValueAsString(ids),model,RetrievalClient.MODEL,elapsed,error};
            jdbc.update(c->{var s=c.prepareStatement("INSERT INTO policy_question(user_id,question,status,answer,evidence_json,citation_ids_json,model,embedding_model,elapsed_ms,error_code) VALUES(?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS); for(int i=0;i<values.length;i++) s.setObject(i+1,values[i]); return s;},key);
            return Objects.requireNonNull(key.getKey()).longValue();
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Cannot store question",ex); }
    }
    @Transactional(readOnly=true)
    public List<Summary> history(PlatformPrincipal user,long before,int size) {
        if(before<1 || size<1 || size>50) throw new BusinessException(HttpStatus.BAD_REQUEST,"分页参数无效");
        return jdbc.query("SELECT id,question,status,created_at FROM policy_question WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?",
            (rs,n)->new Summary(rs.getLong("id"),rs.getString("question"),rs.getString("status"),rs.getTimestamp("created_at").toLocalDateTime()),user.id(),before,size);
    }
    @Transactional(readOnly=true)
    public Detail detail(PlatformPrincipal user,long id) {
        var rows=jdbc.query("SELECT * FROM policy_question WHERE id=? AND user_id=?",(rs,n)->{
            try {
                List<RetrievalStore.Hit> evidence=json.readValue(rs.getString("evidence_json"),new TypeReference<>(){});
                List<Integer> ids=json.readValue(rs.getString("citation_ids_json"),new TypeReference<>(){});
                var states=evidence.stream().map(h->retrieval.historicalAccess(user,h)).toList();
                boolean hidden=states.contains("UNAVAILABLE");
                var citations=new ArrayList<Citation>();
                if(!hidden) for(int index:ids) citations.add(new Citation(index,evidence.get(index-1),states.get(index-1)));
                return new Detail(id,rs.getString("question"),hidden?"RESTRICTED":rs.getString("status"),hidden?"制度来源已失效或访问权限已变更，当前不能展示此历史答案。":rs.getString("answer"),citations,rs.getString("model"),rs.getString("embedding_model"),rs.getLong("elapsed_ms"),hidden?null:rs.getString("error_code"),rs.getTimestamp("created_at").toLocalDateTime());
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Invalid stored question",ex); }
        },id,user.id());
        if(rows.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"问答记录不存在或无访问权限");
        return rows.getFirst();
    }
}
