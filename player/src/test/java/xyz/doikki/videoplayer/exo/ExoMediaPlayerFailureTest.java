package xyz.doikki.videoplayer.exo;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import xyz.doikki.videoplayer.player.PlaybackFailureKind;

public class ExoMediaPlayerFailureTest {
    @Test
    public void classifiesOnlySpecificConnectionFailuresAsUnreachable() {
        assertEquals(PlaybackFailureKind.SOURCE_CONNECTION,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED));
        assertEquals(PlaybackFailureKind.SOURCE_CONNECTION,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT));
        assertEquals(PlaybackFailureKind.UNKNOWN,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED));
        assertEquals(PlaybackFailureKind.UNKNOWN,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_TIMEOUT));
        assertEquals(PlaybackFailureKind.UNKNOWN,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED));
    }

    @Test
    public void identifiesOnlyKnownEngineCompatibilityErrors() {
        assertEquals(PlaybackFailureKind.ENGINE_COMPATIBILITY,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED));
        assertEquals(PlaybackFailureKind.ENGINE_COMPATIBILITY,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED));
        assertEquals(PlaybackFailureKind.UNKNOWN,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED));
        assertEquals(PlaybackFailureKind.UNKNOWN,
                ExoMediaPlayer.classifyFailure(PlaybackException.ERROR_CODE_DECODING_FAILED));
    }
}
