package dev.messageboard.comment;

import dev.messageboard.common.ApiException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class AttachmentService {
    public static final int MAX_FILES = 3;
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private final AttachmentMapper mapper;
    private final UploadQuotaService quota;
    private final Path root;
    public AttachmentService(AttachmentMapper mapper,UploadQuotaService quota, @Value("${board.upload-dir:../.runtime/uploads}") String dir) {
        this.mapper = mapper; this.quota=quota; this.root = Path.of(dir).toAbsolutePath().normalize();
        try { Files.createDirectories(root); } catch (IOException e) { throw new IllegalStateException("无法创建图片目录", e); }
    }
    public record Upload(UUID id, String url, String originalName, String mediaType, long byteSize, int width, int height) {}
    @Transactional(timeout = 10)
    public List<Upload> upload(UUID owner, UUID batchId, List<MultipartFile> files) {
        if (batchId == null) throw ApiException.bad("缺少图片批次编号");
        List<AttachmentMapper.Stored> existing = mapper.byBatch(owner, batchId);
        if (!existing.isEmpty()) return existing.stream().map(this::uploadView).toList();
        if (files == null || files.isEmpty()) throw ApiException.bad("请选择图片");
        if (files.size() > MAX_FILES) throw ApiException.bad("每条留言最多上传 3 张图片");
        long rawBytes=files.stream().filter(Objects::nonNull).mapToLong(MultipartFile::getSize).sum();
        quota.reserve(owner,rawBytes);
        List<Path> written = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                if (file == null || file.isEmpty()) throw ApiException.bad("图片不能为空");
                if (file.getSize() > MAX_BYTES) throw ApiException.bad("单张图片不能超过 5MB");
                String sourceType = detect(file);
                BufferedImage image = readBoundedImage(file.getBytes());
                String format = sourceType.equals("image/jpeg") ? "jpg" : "png";
                String mediaType = format.equals("jpg") ? "image/jpeg" : "image/png";
                UUID id = UUID.randomUUID(); String key = id + "." + format; Path target = safePath(key);
                if (!ImageIO.write(image, format, target.toFile())) throw ApiException.bad("图片编码失败");
                written.add(target); long size = Files.size(target);
                if (size > MAX_BYTES) throw ApiException.bad("处理后的图片不能超过 5MB");
                mapper.save(new AttachmentMapper.Stored(id, owner, batchId, null, i, key, cleanName(file.getOriginalFilename(), format), mediaType, size, image.getWidth(), image.getHeight(), null));
            }
            return mapper.byBatch(owner, batchId).stream().map(this::uploadView).toList();
        } catch (RuntimeException ex) {
            written.forEach(this::deleteQuietly);
            if (ex instanceof ApiException api) throw api;
            throw new ApiException(503, "图片保存失败，请稍后重试");
        } catch (IOException ex) {
            written.forEach(this::deleteQuietly);
            throw new ApiException(503, "图片保存失败，请稍后重试");
        }
    }
    @Transactional(timeout = 10)
    public void attach(UUID owner, UUID comment, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        if (ids.size() > MAX_FILES || new HashSet<>(ids).size() != ids.size()) throw ApiException.bad("图片附件数量不正确");
        List<AttachmentMapper.Stored> rows = mapper.lockPending(owner, ids);
        if (rows.size() != ids.size()) throw new ApiException(409, "图片已过期或不属于当前用户，请重新选择");
        for (int i = 0; i < ids.size(); i++) if (mapper.attach(owner, comment, ids.get(i), i) != 1) throw new ApiException(409, "图片状态已变化，请重新提交");
    }
    public Map<UUID, List<Attachment>> forComments(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of(); Map<UUID,List<Attachment>> out = new HashMap<>();
        for (AttachmentMapper.Stored row : mapper.byComments(ids)) out.computeIfAbsent(row.commentId(), ignored -> new ArrayList<>()).add(view(row));
        return out;
    }
    public AttachmentMapper.Stored visible(UUID id, UUID viewer) {
        AttachmentMapper.Stored row = mapper.visible(id, viewer); if (row == null) throw new ApiException(404, "图片不存在");
        if (!Files.isRegularFile(safePath(row.storageKey()))) throw new ApiException(404, "图片文件不存在"); return row;
    }
    public Resource resource(AttachmentMapper.Stored row) { return new FileSystemResource(safePath(row.storageKey())); }
    public MediaType mediaType(String value) { return MediaType.parseMediaType(value); }
    @Transactional(timeout = 10)
    public void deletePending(UUID owner, UUID id) { if (mapper.lockPendingOne(owner,id)==null) throw new ApiException(404,"待上传图片不存在"); mapper.deletePending(id); }
    @Transactional(timeout = 10)
    public void deleteForComment(UUID comment) { mapper.deleteForComment(comment); }
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    @Transactional(timeout = 30)
    public void purgeDeletedFiles() { for (AttachmentMapper.Stored row : mapper.cleanupCandidates()) if (deleteQuietly(safePath(row.storageKey()))) mapper.markPurged(row.id()); }
    private String detect(MultipartFile file) throws IOException {
        byte[] h=file.getInputStream().readNBytes(12);
        boolean jpeg=h.length>=3&&(h[0]&255)==0xff&&(h[1]&255)==0xd8&&(h[2]&255)==0xff;
        boolean png=h.length>=8&&Arrays.equals(Arrays.copyOf(h,8),new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});
        boolean webp=h.length>=12&&new String(h,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(h,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
        if(jpeg)return "image/jpeg"; if(png)return "image/png"; if(webp)return "image/webp"; throw new ApiException(400,"图片格式无效，只支持 JPG、PNG、WebP");
    }
    private BufferedImage readBoundedImage(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw ApiException.bad("图片内容无法读取");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw ApiException.bad("图片内容无法读取");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION)
                    throw ApiException.bad("图片尺寸不能超过 4096×4096");
                BufferedImage image = reader.read(0);
                if (image == null) throw ApiException.bad("图片内容无法读取");
                return image;
            } finally { reader.dispose(); }
        }
    }
    private String cleanName(String name,String format){String v=name==null?"image."+format:Path.of(name).getFileName().toString();v=v.replaceAll("[\\x00-\\x1f\\\\/\"<>|:*?]","_");return v.isBlank()?"image."+format:(v.length()>120?v.substring(v.length()-120):v);}
    private Path safePath(String key){Path p=root.resolve(key).normalize();if(!p.startsWith(root))throw new IllegalArgumentException("invalid storage key");return p;}
    private Upload uploadView(AttachmentMapper.Stored r){return new Upload(r.id(),"/api/attachments/"+r.id(),r.originalName(),r.mediaType(),r.byteSize(),r.width(),r.height());}
    private Attachment view(AttachmentMapper.Stored r){return new Attachment(r.id(),r.commentId(),"/api/attachments/"+r.id(),r.originalName(),r.mediaType(),r.byteSize(),r.width(),r.height(),r.createdAt());}
    private boolean deleteQuietly(Path p){
        try { Files.deleteIfExists(p); return !Files.exists(p); }
        catch(IOException e){ LoggerFactory.getLogger(AttachmentService.class).warn("无法删除图片文件 {}",p); return false; }
    }
}
