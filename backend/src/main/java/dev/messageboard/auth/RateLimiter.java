package dev.messageboard.auth;

import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.function.LongSupplier;

/** 有界、线程安全的固定窗口；单进程使用。时间源可注入进行边界测试。 */
public final class RateLimiter {
    private final Cache<String,Bucket> buckets=Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(Duration.ofMinutes(2)).build();
    private final LongSupplier now;
    private static final class Bucket { long minute; int used; }
    public RateLimiter() { this(System::currentTimeMillis); }
    public RateLimiter(LongSupplier now) { this.now=now; }
    public boolean allow(String key,int max) {
        Bucket b=buckets.get(key,k -> new Bucket());
        synchronized (b) {
            long minute=now.getAsLong()/60000;
            if(b.minute!=minute) { b.minute=minute; b.used=0; }
            return ++b.used<=max;
        }
    }
}
