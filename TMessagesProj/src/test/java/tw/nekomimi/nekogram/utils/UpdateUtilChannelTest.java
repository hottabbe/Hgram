package tw.nekomimi.nekogram.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UpdateUtilChannelTest {

    @Test
    public void officialChannelIsHgramUpdates() {
        assertEquals("hgram_updates", UpdateUtil.channelUsername);
    }

    @Test
    public void tipsChannelIsKept() {
        assertEquals("NagramTips", UpdateUtil.channelUsernameTips);
    }
}
