package com.enterprise.compliance.request;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/travel-requests")
@PreAuthorize("hasAuthority('approval:submit')")
public class TravelController {
    public record Update(@Positive int contentVersion,@jakarta.validation.constraints.NotNull @Valid TravelStore.Form form) {}
    public record Version(@Positive int contentVersion) {}
    public record Submit(@Positive int contentVersion,@Positive long reviewId) {}
    private final TravelStore store;
    private final TravelReview review;
    public TravelController(TravelStore store,TravelReview review) { this.store=store; this.review=review; }
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal PlatformPrincipal user,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="20") int size) { return ApiResponse.ok(store.list(user,before,size)); }
    @PostMapping public ApiResponse<?> create(@AuthenticationPrincipal PlatformPrincipal user,@Valid @RequestBody TravelStore.Form form) { return ApiResponse.ok(store.create(user,form)); }
    @GetMapping("/{id}") public ApiResponse<?> detail(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id) { return ApiResponse.ok(store.detail(user,id)); }
    @PutMapping("/{id}") public ApiResponse<?> update(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@Valid @RequestBody Update input) { return ApiResponse.ok(store.update(user,id,input.contentVersion(),input.form())); }
    @PostMapping("/{id}/attachments") public ApiResponse<?> attach(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@RequestParam @Positive int contentVersion,@RequestParam MultipartFile file) { return ApiResponse.ok(store.attach(user,id,contentVersion,file)); }
    @DeleteMapping("/{id}/attachments/{attachmentId}") public ApiResponse<?> remove(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@PathVariable long attachmentId,@RequestParam @Positive int contentVersion) { return ApiResponse.ok(store.removeAttachment(user,id,attachmentId,contentVersion)); }
    @GetMapping("/{id}/attachments/{attachmentId}") public ResponseEntity<byte[]> file(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@PathVariable long attachmentId) {
        var d=store.download(user,id,attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.attachment().contentType())).header("X-Content-Type-Options","nosniff")
            .header("Cache-Control","no-store").header("Content-Disposition",ContentDisposition.attachment().filename(d.attachment().filename(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(d.bytes());
    }
    @PostMapping("/{id}/reviews") public ApiResponse<?> review(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@Valid @RequestBody Version input) { return ApiResponse.ok(review.review(user,id,input.contentVersion())); }
    @PostMapping("/{id}/submit") public ApiResponse<?> submit(@AuthenticationPrincipal PlatformPrincipal user,@PathVariable long id,@Valid @RequestBody Submit input) { return ApiResponse.ok(store.submit(user,id,input.contentVersion(),input.reviewId())); }
}
