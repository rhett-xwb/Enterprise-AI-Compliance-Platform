package com.enterprise.compliance.request;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.enterprise.compliance.document.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TravelReview {
    private final TravelStore store;
    private final RetrievalController search;
    private final QuestionModel model;
    private final boolean enabled;
    private final Semaphore slots=new Semaphore(4);
    public TravelReview(TravelStore store,RetrievalController search,QuestionModel model,@Value("${app.qa.enabled:false}") boolean enabled) {
        this.store=store; this.search=search; this.model=model; this.enabled=enabled;
    }
    public TravelStore.Detail review(PlatformPrincipal user,long id,int version) {
        var d=store.detail(user,id);
        if(!d.status().equals("DRAFT") || d.contentVersion()!=version) throw new BusinessException(HttpStatus.CONFLICT,"申请内容已变化，请刷新");
        if(!slots.tryAcquire()) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"预审繁忙，请稍后重试");
        try {
            var issues=store.rules(d.form());
            String status="UNKNOWN",risk=issues.isEmpty()?"UNKNOWN":"HIGH",advice="无法判断制度风险：尚无足够授权制度依据。请交由人工核对。",error=null;
            List<RetrievalStore.Hit> evidence=List.of(); List<Integer> citations=List.of();
            if(enabled) try {
                evidence=search.search(new RetrievalController.Query("差旅 出差 申请 金额 费用 目的地 "+Objects.toString(d.form().destination(),""),5,0.5),user).data();
                if(!evidence.isEmpty()) {
                    String question="请根据制度检查差旅申请并解释可能的问题与修改建议，不作审批决定。附件仅有元数据，未解析附件内容，不得声称已审查附件。资料不足明确无法判断。申请："+d.form()+"；附件文件名："+d.attachments().stream().map(item -> item.filename()).toList();
                    var result=model.generate(question,evidence);
                    if(result.status().equals("INSUFFICIENT")) { evidence=List.of(); }
                    else { status=result.status().equals("CONFLICT")?"CONFLICT":"REVIEWED"; risk=issues.isEmpty()?"REVIEW_REQUIRED":"HIGH"; advice=result.answer(); citations=result.citations(); }
                }
            } catch(QuestionModel.Failure ex) { status="FAILED"; error=ex.code; }
              catch(BusinessException ex) { status="FAILED"; error="RETRIEVAL_UNAVAILABLE"; }
            if(status.equals("FAILED")) { evidence=List.of(); citations=List.of(); advice="预审未完成，请重试。"; }
            return store.saveReview(user,id,new TravelStore.Review(0,version,status,risk,issues,advice,evidence,citations,model.model(),error,LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
        } finally { slots.release(); }
    }
}
