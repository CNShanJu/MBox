package xyz.doikki.videoplayer.ijk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import xyz.doikki.videoplayer.player.PlaybackFailureKind;

public class IjkPlayerFailureTest {
    @Test
    public void genericNativeErrorsAreNotAssumedToBeConnectionFailures() {
        assertEquals(PlaybackFailureKind.UNKNOWN, IjkPlayer.classifyFailure(IMediaPlayer.MEDIA_ERROR_IO));
        assertEquals(PlaybackFailureKind.UNKNOWN, IjkPlayer.classifyFailure(IMediaPlayer.MEDIA_ERROR_TIMED_OUT));
        assertEquals(PlaybackFailureKind.UNKNOWN, IjkPlayer.classifyFailure(-10000));
        assertEquals(PlaybackFailureKind.ENGINE_COMPATIBILITY,
                IjkPlayer.classifyFailure(IMediaPlayer.MEDIA_ERROR_UNSUPPORTED));
    }
}
