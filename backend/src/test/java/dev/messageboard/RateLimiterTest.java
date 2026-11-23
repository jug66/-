package dev.messageboard;

import dev.messageboard.auth.RateLimiter;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class RateLimiterTest {
    @Test void boundaryRecoveryAndIndependentKeys() throws Exception {
        AtomicLong now=new AtomicLong(0);
        RateLimiter limiter=new RateLimiter(now::get);
        try(var pool=Executors.newFixedThreadPool(12)) {
            var jobs=new java.util.ArrayList<Callable<Boolean>>();
            for(int i=0;i<60;i++) jobs.add(() -> limiter.allow("same-ip",30));
            int allowed=0; for(var result:pool.invokeAll(jobs)) if(result.get())allowed++;
            assertThat(allowed).isEqualTo(30);
        }
        assertThat(limiter.allow("different-ip",30)).isTrue();
        now.set(59999); assertThat(limiter.allow("same-ip",30)).isFalse();
        now.set(60000); assertThat(limiter.allow("same-ip",30)).isTrue();
    }
}
