package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/questions")
@PreAuthorize("hasAuthority('document:read')")
public class QuestionController {
    public record Ask(@NotBlank @Size(max=500) String question) {}
    private final RetrievalController search;
    private final RetrievalStore retrieval;
    private final QuestionModel model;
    private final QuestionStore store;
    private final boolean enabled;
    private final Semaphore slots=new Semaphore(4);
    public QuestionController(RetrievalController search,RetrievalStore retrieval,QuestionModel model,QuestionStore store,@Value("${app.qa.enabled:false}") boolean enabled) {
        this.search=search; this.retrieval=retrieval; this.model=model; this.store=store; this.enabled=enabled;
    }
    @PostMapping
    public ApiResponse<QuestionStore.Detail> ask(@Valid @RequestBody Ask input,@AuthenticationPrincipal PlatformPrincipal user) {
        if(!enabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"制度问答尚未启用，请联系管理员配置模型");
        if(!slots.tryAcquire()) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"问答服务繁忙，请稍后重试");
        long start=System.nanoTime();
        try {
            String status="INSUFFICIENT",answer="当前授权的有效制度中没有足够依据，请调整问题或联系法务核对。",error=null;
            List<RetrievalStore.Hit> evidence=List.of(); List<Integer> ids=List.of();
            try {
                evidence=search.search(new RetrievalController.Query(input.question(),5,0.5),user).data();
                if(!evidence.isEmpty()) {
                    var result=model.generate(input.question().trim(),evidence);
                    if(!retrieval.stillCurrent(user,evidence)) throw new QuestionModel.Failure("SOURCE_CHANGED");
                    status=result.status(); ids=result.citations();
                    if(!status.equals("INSUFFICIENT")) answer=result.answer();
                }
            } catch(QuestionModel.Failure ex) { status="FAILED"; error=ex.code; }
            catch(BusinessException ex) { status="FAILED"; error="RETRIEVAL_UNAVAILABLE"; }
            if(status.equals("FAILED")) { answer="问答未完成，模型或来源暂不可用，请稍后重试。"; evidence=List.of(); ids=List.of(); }
            long id=store.save(user,input.question().trim(),status,answer,evidence,ids,model.model(),(System.nanoTime()-start)/1_000_000,error);
            return ApiResponse.ok(store.detail(user,id));
        } finally { slots.release(); }
    }
    @GetMapping
    public ApiResponse<List<QuestionStore.Summary>> history(@AuthenticationPrincipal PlatformPrincipal user,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(store.history(user,before,size));
    }
    @GetMapping("/{id}")
    public ApiResponse<QuestionStore.Detail> detail(@PathVariable long id,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(store.detail(user,id)); }
    @GetMapping("/{id}/citations/{citationId}")
    public ApiResponse<QuestionStore.Citation> source(@PathVariable long id,@PathVariable int citationId,@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(store.detail(user,id).citations().stream().filter(c->c.id()==citationId).findFirst()
            .orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"引用不存在或无访问权限")));
    }
}
