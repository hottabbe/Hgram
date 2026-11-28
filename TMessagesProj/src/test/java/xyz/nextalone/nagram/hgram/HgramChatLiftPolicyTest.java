package xyz.nextalone.nagram.hgram;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramChatLiftPolicyTest {
    @Test
    public void privateChatAndGroupsLift() {
        assertTrue(HgramChatLiftPolicy.supports(null));
        assertTrue(HgramChatLiftPolicy.supports(new TLRPC.TL_chat()));
        TLRPC.TL_channel megagroup = new TLRPC.TL_channel();
        megagroup.megagroup = true;
        assertTrue(HgramChatLiftPolicy.supports(megagroup));
    }

    @Test
    public void broadcastChannelNeverLifts() {
        TLRPC.TL_channel channel = new TLRPC.TL_channel();
        channel.megagroup = false;
        channel.broadcast = true;
        assertFalse(HgramChatLiftPolicy.supports(channel));
    }
}
