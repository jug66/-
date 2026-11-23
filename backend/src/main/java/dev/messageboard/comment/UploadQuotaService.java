package dev.messageboard.comment;

import dev.messageboard.common.ApiException;
import org.apache.ibatis.annotations.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class UploadQuotaService {
    private final AttachmentMapper attachments;
    private final UsageMapper usage;
    private final long userDailyBytes;
    private final long pendingBytes;
    public UploadQuotaService(AttachmentMapper attachments,UsageMapper usage,
                               @Value("${board.upload.user-daily-bytes:104857600}") long userDailyBytes,
                               @Value("${board.upload.pending-bytes:52428800}") long pendingBytes) {
        this.attachments=attachments; this.usage=usage; this.userDailyBytes=userDailyBytes; this.pendingBytes=pendingBytes;
    }
    @Transactional(timeout=10)
    public void reserve(UUID owner,long bytes) {
        if(bytes<1 || bytes>pendingBytes || attachments.pendingBytes(owner)+bytes>pendingBytes)
            throw new ApiException(429,"待发布图片已达到容量上限，请先发布或移除已有图片");
        if(usage.reserve("user:"+owner, LocalDate.now(), bytes, userDailyBytes)!=1)
            throw new ApiException(429,"今日图片上传额度已用尽，请明天再试");
    }
    @Mapper
    interface UsageMapper {
        @Insert("INSERT INTO upload_usage(scope_key,usage_day,byte_size) VALUES(#{scope},#{day},#{bytes}) ON CONFLICT(scope_key,usage_day) DO UPDATE SET byte_size=upload_usage.byte_size+EXCLUDED.byte_size WHERE upload_usage.byte_size+EXCLUDED.byte_size<=#{limit}")
        int reserve(@Param("scope") String scope,@Param("day") LocalDate day,@Param("bytes") long bytes,@Param("limit") long limit);
    }
}
