package dev.messageboard.comment;

import dev.messageboard.auth.User;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AttachmentController {
    private final AttachmentService service;
    private final ModerationService moderation;
    public AttachmentController(AttachmentService service,ModerationService moderation) { this.service = service; this.moderation=moderation; }
    @PostMapping(value="/uploads/images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<AttachmentService.Upload> upload(@AuthenticationPrincipal User.View user,@RequestParam UUID batchId,@RequestPart("files") List<MultipartFile> files) {
        moderation.requireActive(user.id()); return service.upload(user.id(),batchId,files);
    }
    @DeleteMapping("/uploads/images/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePending(@AuthenticationPrincipal User.View user,@PathVariable UUID id){service.deletePending(user.id(),id);}
    @GetMapping("/attachments/{id}")
    public ResponseEntity<Resource> get(@PathVariable UUID id,@AuthenticationPrincipal User.View user){
        AttachmentMapper.Stored row=service.visible(id,user==null?null:user.id());
        return ResponseEntity.ok().contentType(service.mediaType(row.mediaType())).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(row.originalName()).build().toString()).body(service.resource(row));
    }
}
