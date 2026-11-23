package org.telegram.messenger;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Round-trips the production push placeholder builder through the TL layer and checks the two
 * contracts that SQLite and {@code randoms_v2} depend on: the forum topic ids and random_id = 0.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PushChatSyncPlaceholderTest {

    /** TL_message serialization asks ApplicationLoader; the test subclass disables attach paths. */
    private static final class TestApplicationLoader extends ApplicationLoader {
        @Override
        protected boolean isAndroidTestEnv() {
            return true;
        }
    }

    @Before
    public void installTestApplicationLoader() throws Exception {
        Field field = ApplicationLoader.class.getDeclaredField("applicationLoaderInstance");
        field.setAccessible(true);
        if (field.get(null) == null) {
            field.set(null, new TestApplicationLoader());
        }
    }

    private static TLRPC.TL_message topicPush() {
        TLRPC.TL_message source = new TLRPC.TL_message();
        source.id = 1001;
        source.random_id = 987654321L;
        source.dialog_id = -1001234567890L;
        source.date = 1700000000;
        source.message = "short push preview";

        TLRPC.TL_peerChannel channel = new TLRPC.TL_peerChannel();
        channel.channel_id = 1234567890L;
        source.peer_id = channel;

        TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader();
        reply.forum_topic = true;
        reply.reply_to_top_id = 555;
        reply.reply_to_msg_id = 444;
        source.reply_to = reply;
        return source;
    }

    @Test
    public void topicPlaceholderRoundTripsAndPreservesTopicId() {
        TLRPC.TL_message placeholder = PushChatSync.buildPlaceholder(topicPush(), -1001234567890L);
        assertNotNull(placeholder);

        // Outer reply bit is 1 << 3 (MESSAGE_FLAG_REPLY); bit 2 (MESSAGE_FLAG_FWD) must stay clear
        // because the placeholder never carries fwd_from.
        assertTrue("reply bit", (placeholder.flags & TLRPC.MESSAGE_FLAG_REPLY) != 0);
        assertTrue("fwd bit must be clear", (placeholder.flags & TLRPC.MESSAGE_FLAG_FWD) == 0);
        assertNull("no fwd_from on a preview", placeholder.fwd_from);
        assertNotNull(placeholder.reply_to);
        assertTrue(placeholder.reply_to.forum_topic);
        assertEquals(555, placeholder.reply_to.reply_to_top_id);
        assertEquals(444, placeholder.reply_to.reply_to_msg_id);

        SerializedData out = new SerializedData();
        placeholder.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        int constructor = in.readInt32(false);
        TLRPC.Message parsed = TLRPC.Message.TLdeserialize(in, constructor, false);
        assertNotNull(parsed);

        assertNotNull("reply_to survives TL serialization", parsed.reply_to);
        assertTrue(parsed.reply_to.forum_topic);
        assertEquals(555, parsed.reply_to.reply_to_top_id);
        assertEquals(444, parsed.reply_to.reply_to_msg_id);
        assertTrue("reply bit survives TL serialization",
                (parsed.flags & TLRPC.MESSAGE_FLAG_REPLY) != 0);
        assertTrue((parsed.flags & TLRPC.MESSAGE_FLAG_FWD) == 0);
        assertNull(parsed.fwd_from);

        // Exact expression used for the messages_topics.topic_id column in putMessagesInternal.
        long topicId = parsed.reply_to.reply_to_top_id != 0
                ? parsed.reply_to.reply_to_top_id
                : parsed.reply_to.reply_to_msg_id;
        assertEquals("topic id stored in messages_topics", 555L, topicId);
    }

    @Test
    public void pushRandomIdIsNotCopiedIntoPlaceholder() {
        TLRPC.TL_message source = topicPush();
        source.reply_to = null;
        long randomId = source.random_id;
        assertTrue(randomId != 0);

        TLRPC.TL_message placeholder = PushChatSync.buildPlaceholder(source, source.dialog_id);
        assertNotNull(placeholder);
        assertEquals("server message id is preserved", 1001, placeholder.id);
        assertEquals("incoming random_id must not enter randoms_v2", 0, placeholder.random_id);
        assertTrue("no reply on a plain message",
                (placeholder.flags & TLRPC.MESSAGE_FLAG_REPLY) == 0);
        assertNull(placeholder.reply_to);
        assertFalse(placeholder.out);
    }
}
