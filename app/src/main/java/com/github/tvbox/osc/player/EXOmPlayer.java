package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;

import java.util.List;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;
import xyz.doikki.videoplayer.exo.ExoTrackNameProvider;

/** Media3 音轨/字幕适配器，原播放类型 2 和工厂入口保持兼容。 */
@UnstableApi
public class EXOmPlayer extends ExoMediaPlayer implements KernelTrackSupport {
    private Player.Listener subtitleListener;

    public EXOmPlayer(Context context) {
        super(context);
    }

    @Override
    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        if (mMediaPlayer == null) return data;
        if (trackNameProvider == null) {
            trackNameProvider = new ExoTrackNameProvider(mAppContext.getResources());
        }
        List<Tracks.Group> groups = mMediaPlayer.getCurrentTracks().getGroups();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            Tracks.Group group = groups.get(groupIndex);
            int type = group.getType();
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                Format format = group.getTrackFormat(trackIndex);
                TrackInfoBean track = new TrackInfoBean();
                track.name = trackNameProvider.getTrackName(format);
                if (type == C.TRACK_TYPE_AUDIO) {
                    track.name += "[" + (TextUtils.isEmpty(format.codecs)
                            ? format.sampleMimeType : format.codecs) + "]";
                }
                track.language = format.language == null ? "" : format.language;
                track.trackId = trackIndex;
                track.trackGroupId = groupIndex;
                track.renderId = C.INDEX_UNSET;
                // 不依赖 Format.id：无 ID 或重复 ID 的轨道也能准确显示选中状态。
                track.selected = group.isTrackSelected(trackIndex);
                if (type == C.TRACK_TYPE_AUDIO) data.addAudio(track);
                else data.addSubtitle(track);
            }
        }
        return data;
    }

    public void selectExoTrack(@Nullable TrackInfoBean track) {
        if (mMediaPlayer == null) return;
        if (track == null) {
            mMediaPlayer.setTrackSelectionParameters(mMediaPlayer.getTrackSelectionParameters()
                    .buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            return;
        }
        List<Tracks.Group> groups = mMediaPlayer.getCurrentTracks().getGroups();
        if (track.trackGroupId < 0 || track.trackGroupId >= groups.size()) return;
        Tracks.Group group = groups.get(track.trackGroupId);
        if (track.trackId < 0 || track.trackId >= group.length) return;
        int type = group.getType();
        if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) return;
        mMediaPlayer.setTrackSelectionParameters(mMediaPlayer.getTrackSelectionParameters()
                .buildUpon().setTrackTypeDisabled(type, false)
                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), track.trackId))
                .build());
    }

    @Override
    public void selectTrack(@Nullable TrackInfoBean track) {
        if (track != null) selectExoTrack(track);
    }

    @Override
    public boolean requiresControllerProgressRestart() {
        return true;
    }

    @Override
    public void setOnSubtitleListener(PlayerTrackHelper.SubtitleListener listener) {
        if (mMediaPlayer == null) return;
        if (subtitleListener != null) mMediaPlayer.removeListener(subtitleListener);
        subtitleListener = null;
        if (listener == null) return;
        subtitleListener = new Player.Listener() {
            @Override
            public void onCues(@NonNull CueGroup cueGroup) {
                try {
                    if (!cueGroup.cues.isEmpty() && cueGroup.cues.get(0).text != null) {
                        listener.onSubtitle(cueGroup.cues.get(0).text.toString());
                    } else {
                        listener.onSubtitle(null);
                    }
                } catch (Throwable th) {
                    android.util.Log.w("PlayerTrackHelper", "Media3 cue 回调异常: " + th.getMessage());
                }
            }
        };
        mMediaPlayer.addListener(subtitleListener);
    }

    @Override
    public void release() {
        if (mMediaPlayer != null && subtitleListener != null) {
            mMediaPlayer.removeListener(subtitleListener);
        }
        subtitleListener = null;
        super.release();
    }
}
