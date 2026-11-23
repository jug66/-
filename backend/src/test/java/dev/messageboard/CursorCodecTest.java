package dev.messageboard;

import dev.messageboard.comment.CursorCodec;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CursorCodecTest {
    private final CursorCodec codec = new CursorCodec("01234567890123456789012345678901", false);

    @Test
    void signsAncestorContinuationAndRejectsDifferentTarget() {
        UUID target = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        String token = codec.encodeAncestor(target, next);
        assertThat(codec.decodeAncestor(token, target)).isEqualTo(next);
        assertThatThrownBy(() -> codec.decodeAncestor(token, UUID.randomUUID()))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void signsRecentReplyCursor() {
        var row = new dev.messageboard.comment.Comment(UUID.randomUUID(), UUID.randomUUID(), "u", UUID.randomUUID(), UUID.randomUUID(), "p", "x", OffsetDateTime.now(), null, false, 0, null);
        String token = codec.encodeRecent(row);
        var position = codec.decodeRecent(token);
        assertThat(position.id()).isEqualTo(row.id());
    }
}
