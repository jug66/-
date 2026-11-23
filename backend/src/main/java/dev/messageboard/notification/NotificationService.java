package dev.messageboard.notification;

import dev.messageboard.comment.Comment;
import dev.messageboard.comment.CursorCodec;
import dev.messageboard.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class NotificationService {
    private final NotificationMapper mapper;
    private final CursorCodec cursors;
    public NotificationService(NotificationMapper mapper, CursorCodec cursors) { this.mapper=mapper; this.cursors=cursors; }
    public record Page(List<Notification> items, String nextCursor, boolean hasMore) {}
    public void reply(UUID actor, Comment parent, UUID replyId) {
        if (parent == null || parent.userId() == null || parent.userId().equals(actor)) return;
        mapper.insert(UUID.randomUUID(), parent.userId(), actor, replyId, "reply", "reply:"+replyId);
    }
    public Page page(UUID recipient, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw ApiException.bad("每页通知数应为 1～100");
        var position=cursors.decodeNotification(cursor,recipient);
        var rows=mapper.page(recipient,position.time(),position.id(),limit+1);
        boolean more=rows.size()>limit;
        var items=rows.subList(0,Math.min(limit,rows.size()));
        String next = more && !items.isEmpty() ? cursors.encodeNotification(recipient,
            items.getLast().createdAt(), items.getLast().id()) : null;
        return new Page(items,next,more);
    }
    @Transactional(timeout=10)
    public void read(UUID recipient, UUID id) {
        if (mapper.markRead(id,recipient) != 1) throw new ApiException(404,"通知不存在");
    }
}
