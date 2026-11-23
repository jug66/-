package dev.messageboard.comment;

import dev.messageboard.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class CursorCodec {
    private final byte[] secret;
    private static final Base64.Encoder ENCODE=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE=Base64.getUrlDecoder();
    public CursorCodec(@Value("${board.cursor-secret:}") String secret,@Value("${board.require-stable-secrets:false}") boolean requireStable) {
        if(requireStable && secret.isBlank()) throw new IllegalStateException("生产环境必须配置 CURSOR_SECRET");
        this.secret=secret.isBlank()?random():secret.getBytes(StandardCharsets.UTF_8);
        if(this.secret.length<32) throw new IllegalArgumentException("CURSOR_SECRET must have at least 32 bytes");
    }
    private byte[] random() { byte[] key=new byte[32]; new SecureRandom().nextBytes(key); return key; }
    private byte[] sign(byte[] data) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); return mac.doFinal(data); }
        catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public record Position(OffsetDateTime time,UUID id) {}
    public String encode(UUID parent,boolean newest,Comment row) {
        byte[] bytes=(parent+"|"+newest+"|"+row.createdAt()+"|"+row.id()).getBytes(StandardCharsets.UTF_8);
        return ENCODE.encodeToString(bytes)+"."+ENCODE.encodeToString(sign(bytes));
    }
    public Position decode(String cursor,UUID parent,boolean newest) {
        if(cursor==null) return new Position(null,null);
        try {
            if(cursor.length()>512) throw new IllegalArgumentException();
            String[] parts=cursor.split("\\.",-1);
            if(parts.length!=2) throw new IllegalArgumentException();
            byte[] bytes=DECODE.decode(parts[0]);
            if(!MessageDigest.isEqual(sign(bytes),DECODE.decode(parts[1]))) throw new IllegalArgumentException();
            String[] data=new String(bytes,StandardCharsets.UTF_8).split("\\|",-1);
            if(data.length!=4 || !data[0].equals(String.valueOf(parent)) || !data[1].equals(String.valueOf(newest))) throw new IllegalArgumentException();
            return new Position(OffsetDateTime.parse(data[2]),UUID.fromString(data[3]));
        } catch(RuntimeException ex) { throw ApiException.bad("分页游标无效，请重新加载当前列表"); }
    }

    public String encodeAncestor(UUID target, UUID nextParent) {
        return signed("ancestor|" + target + "|" + nextParent);
    }

    public UUID decodeAncestor(String cursor, UUID target) {
        String[] data = verify(cursor, "ancestor");
        if (data.length != 3 || !data[1].equals(String.valueOf(target)))
            throw ApiException.bad("祖先游标无效，请重新打开当前留言");
        try { return UUID.fromString(data[2]); }
        catch (RuntimeException ex) { throw ApiException.bad("祖先游标无效，请重新打开当前留言"); }
    }

    public String encodeRecent(Comment row) {
        return signed("recent|" + row.createdAt() + "|" + row.id());
    }

    public Position decodeRecent(String cursor) {
        if (cursor == null) return new Position(null, null);
        String[] data = verify(cursor, "recent");
        if (data.length != 3) throw ApiException.bad("最近回复游标无效，请重新加载");
        try { return new Position(OffsetDateTime.parse(data[1]), UUID.fromString(data[2])); }
        catch (RuntimeException ex) { throw ApiException.bad("最近回复游标无效，请重新加载"); }
    }

    public String encodeNotification(UUID recipient, OffsetDateTime time, UUID id) {
        return signed("notification|" + recipient + "|" + time + "|" + id);
    }

    public Position decodeNotification(String cursor, UUID recipient) {
        if (cursor == null) return new Position(null, null);
        String[] data = verify(cursor, "notification");
        if (data.length != 4 || !data[1].equals(String.valueOf(recipient)))
            throw ApiException.bad("通知游标无效，请重新加载");
        try { return new Position(OffsetDateTime.parse(data[2]), UUID.fromString(data[3])); }
        catch (RuntimeException ex) { throw ApiException.bad("通知游标无效，请重新加载"); }
    }

    public String encodeSearch(String query, Comment row) {
        return signed("search|" + query + "|" + row.createdAt() + "|" + row.id());
    }

    public Position decodeSearch(String cursor, String query) {
        if (cursor == null) return new Position(null, null);
        String[] data = verify(cursor, "search");
        if (data.length != 4 || !data[1].equals(query)) throw ApiException.bad("搜索游标与关键词不匹配");
        try { return new Position(OffsetDateTime.parse(data[2]), UUID.fromString(data[3])); }
        catch (RuntimeException ex) { throw ApiException.bad("搜索游标无效，请重新搜索"); }
    }

    private String signed(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return ENCODE.encodeToString(bytes) + "." + ENCODE.encodeToString(sign(bytes));
    }

    private String[] verify(String cursor, String type) {
        try {
            if (cursor == null || cursor.length() > 512) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.",-1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] bytes = DECODE.decode(parts[0]);
            if (!MessageDigest.isEqual(sign(bytes), DECODE.decode(parts[1]))) throw new IllegalArgumentException();
            String[] data = new String(bytes, StandardCharsets.UTF_8).split("\\|",-1);
            if (data.length == 0 || !data[0].equals(type)) throw new IllegalArgumentException();
            return data;
        } catch (RuntimeException ex) { throw ApiException.bad("分页游标无效，请重新加载当前列表"); }
    }
}
