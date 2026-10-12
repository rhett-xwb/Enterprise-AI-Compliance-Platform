package com.enterprise.compliance.request;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowStore store;
    private final TravelStore travel;
    public WorkflowController(WorkflowStore store,TravelStore travel) { this.store=store; this.travel=travel; }
    @GetMapping("/requests/{id}/materials") @PreAuthorize("hasAuthority('approval:review')")
    public ApiResponse<?> materials(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id) { return ApiResponse.ok(travel.reviewerDetail(user,id)); }
    @GetMapping("/requests/{id}/attachments/{attachmentId}") @PreAuthorize("hasAuthority('approval:review')")
    public org.springframework.http.ResponseEntity<byte[]> download(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@PathVariable long attachmentId) {
        var d=travel.reviewerDownload(user,id,attachmentId);
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(d.attachment().contentType()))
            .header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store")
            .header("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename(d.attachment().filename(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(d.bytes());
    }
    @GetMapping("/definitions") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> definitions() { return ApiResponse.ok(store.definitions()); }
    @GetMapping("/assignees") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> assignees() { return ApiResponse.ok(store.assignees()); }
    @PostMapping("/definitions") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> publish(@AuthenticationPrincipal PlatformPrincipal user,@Valid @RequestBody WorkflowStore.Config config) { return ApiResponse.ok(store.publish(user,config)); }
    @GetMapping("/tasks") @PreAuthorize("hasAuthority('approval:review')")
    public ApiResponse<?> tasks(@AuthenticationPrincipal PlatformPrincipal user,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean history) { return ApiResponse.ok(store.inbox(user,before,size,history)); }
    @GetMapping("/tasks/{id}") @PreAuthorize("hasAuthority('approval:review')")
    public ApiResponse<?> task(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id) { return ApiResponse.ok(store.taskDetail(user,id)); }
    @PostMapping("/tasks/{id}/decisions") @PreAuthorize("hasAuthority('approval:review')")
    public ApiResponse<?> decide(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@Valid @RequestBody WorkflowStore.Decision input) { store.decide(user,id,input); return ApiResponse.ok(store.taskDetail(user,id)); }
    @GetMapping("/requests/{id}/history") @PreAuthorize("hasAuthority('approval:submit') or hasAuthority('approval:review')")
    public ApiResponse<?> history(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id) { return ApiResponse.ok(store.history(user,id)); }
    @PostMapping("/requests/{id}/withdraw") @PreAuthorize("hasAuthority('approval:submit')")
    public ApiResponse<?> withdraw(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@Valid @RequestBody TravelController.Version version) { store.withdraw(user,id,version.contentVersion()); return ApiResponse.ok(store.history(user,id)); }
    @PostMapping("/requests/{id}/reopen") @PreAuthorize("hasAuthority('approval:submit')")
    public ApiResponse<?> reopen(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@Valid @RequestBody TravelController.Version version) { store.reopen(user,id,version.contentVersion()); return ApiResponse.ok(store.history(user,id)); }
}
