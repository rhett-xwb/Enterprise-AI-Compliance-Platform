package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.ApiResponse;
import com.enterprise.compliance.user.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service=service; }
    @GetMapping
    @PreAuthorize("hasAuthority('document:read') or hasAuthority('document:manage')")
    ApiResponse<PageResponse<DocumentService.DocumentRow>> list(@RequestParam(defaultValue="false") boolean management,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size,@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.list(user,management,page,size));
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('document:read') or hasAuthority('document:manage')")
    ApiResponse<DocumentService.Detail> detail(@PathVariable @Positive long id,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.detail(id,user)); }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('document:manage')")
    ResponseEntity<ApiResponse<DocumentService.Detail>> create(@RequestPart("metadata") @Valid DocumentService.CreateInput input,
            @RequestPart("file") MultipartFile file,@AuthenticationPrincipal PlatformPrincipal user) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.create(input,file,user)));
    }
    @PostMapping(value="/{id}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('document:manage')")
    ResponseEntity<ApiResponse<DocumentService.Detail>> upload(@PathVariable @Positive long id,@RequestPart("metadata") @Valid DocumentService.VersionInput input,
            @RequestPart("file") MultipartFile file,@AuthenticationPrincipal PlatformPrincipal user) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.upload(id,input,file,user)));
    }
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('document:manage')")
    ApiResponse<DocumentService.Detail> update(@PathVariable @Positive long id,@RequestBody @Valid DocumentService.Metadata input,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.update(id,input,user)); }
    @PostMapping("/{id}/versions/{versionId}/publish")
    @PreAuthorize("hasAuthority('document:manage')")
    ApiResponse<DocumentService.Detail> publish(@PathVariable @Positive long id,@PathVariable @Positive long versionId,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.publish(id,versionId,user)); }
    @PostMapping("/{id}/versions/{versionId}/retire")
    @PreAuthorize("hasAuthority('document:manage')")
    ApiResponse<DocumentService.Detail> retire(@PathVariable @Positive long id,@PathVariable @Positive long versionId,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.retire(id,versionId,user)); }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('document:manage')")
    ApiResponse<Void> delete(@PathVariable @Positive long id,@AuthenticationPrincipal PlatformPrincipal user) { service.delete(id,user); return ApiResponse.ok(null); }
    @GetMapping("/{id}/audit")
    @PreAuthorize("hasAuthority('document:manage')")
    ApiResponse<List<DocumentService.Audit>> history(@PathVariable @Positive long id,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.history(id,user)); }
    @GetMapping("/{id}/versions/{versionId}/file")
    @PreAuthorize("hasAuthority('document:read') or hasAuthority('document:manage')")
    ResponseEntity<byte[]> download(@PathVariable @Positive long id,@PathVariable @Positive long versionId,@AuthenticationPrincipal PlatformPrincipal user) {
        var download=service.download(id,versionId,user);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.type()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(download.filename(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(download.bytes());
    }
}
