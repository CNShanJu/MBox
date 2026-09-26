package com.github.tvbox.osc.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.StringUtils;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector.SelectionOverride;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.List;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;

public class EXOmPlayer extends ExoMediaPlayer implements KernelTrackSupport {
    private String audioId = "";
    private String subtitleId = "";

    public EXOmPlayer(Context context) {
        super(context);
    }

    @SuppressLint("UnsafeOptInUsageError")
    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        MappingTrackSelector.MappedTrackInfo trackInfo = getTrackSelector().getCurrentMappedTrackInfo();
        if (trackInfo != null) {
            getExoSelectedTrack();
            for (int groupArrayIndex = 0; groupArrayIndex < trackInfo.getRendererCount(); groupArrayIndex++) {
                TrackGroupArray groupArray = trackInfo.getTrackGroups(groupArrayIndex);
                for (int groupIndex = 0; groupIndex < groupArray.length; groupIndex++) {
                    TrackGroup group = groupArray.get(groupIndex);
                    for (int formatIndex = 0; formatIndex < group.length; formatIndex++) {
                        Format format = group.getFormat(formatIndex);
                        if (MimeTypes.isAudio(format.sampleMimeType)) {
                            String trackName = trackNameProvider.getTrackName(format) + "[" + (TextUtils.isEmpty(format.codecs)?format.sampleMimeType:format.codecs) + "]";
                            TrackInfoBean t = new TrackInfoBean();
                            t.name = trackName;
                            t.language = "";
                            t.trackId = formatIndex;
                            t.selected = !StringUtils.isEmpty(audioId) && audioId.equals(format.id);
                            t.trackGroupId = groupIndex;
                            t.renderId = groupArrayIndex;
                            data.addAudio(t);
                        } else if (MimeTypes.isText(format.sampleMimeType)) {
                            String trackName = trackNameProvider.getTrackName(format);
                            TrackInfoBean t = new TrackInfoBean();
                            t.name = trackName;
                            t.language = "";
                            t.trackId = formatIndex;
                            t.selected = !StringUtils.isEmpty(subtitleId) && subtitleId.equals(format.id);
                            t.trackGroupId = groupIndex;
                            t.renderId = groupArrayIndex;
                            data.addSubtitle(t);
                        }
                    }
                }
            }
        }
        return data;
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void getExoSelectedTrack() {
        audioId = "";
        subtitleId = "";
        for (Tracks.Group group : mMediaPlayer.getCurrentTracks().getGroups()) {
            if (!group.isSelected()) continue;
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                if (!group.isTrackSelected(trackIndex)) continue;
                Format format = group.getTrackFormat(trackIndex);
                if (MimeTypes.isAudio(format.sampleMimeType)) {
                    audioId = format.id;
                }
                if (MimeTypes.isText(format.sampleMimeType)) {
                    subtitleId = format.id;
                }
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    public void selectExoTrack(@Nullable TrackInfoBean videoTrackBean) {
        MappingTrackSelector.MappedTrackInfo trackInfo = getTrackSelector().getCurrentMappedTrackInfo();
        if (trackInfo != null) {
            if (videoTrackBean == null) {
                for (int renderIndex = 0; renderIndex < trackInfo.getRendererCount(); renderIndex++) {
                    if (trackInfo.getRendererType(renderIndex) == C.TRACK_TYPE_TEXT) {
                        DefaultTrackSelector.Parameters.Builder parametersBuilder = getTrackSelector().getParameters().buildUpon();
                        parametersBuilder.setRendererDisabled(renderIndex, true);
                        getTrackSelector().setParameters(parametersBuilder);
                        break;
                    }
                }
            } else {
                TrackGroupArray trackGroupArray = trackInfo.getTrackGroups(videoTrackBean.renderId);
                @SuppressLint("UnsafeOptInUsageError") DefaultTrackSelector.SelectionOverride override = new DefaultTrackSelector.SelectionOverride(videoTrackBean.trackGroupId, videoTrackBean.trackId);
                DefaultTrackSelector.Parameters.Builder parametersBuilder = getTrackSelector().buildUponParameters();
                parametersBuilder.setRendererDisabled(videoTrackBean.renderId, false);
                parametersBuilder.setSelectionOverride(videoTrackBean.renderId, trackGroupArray, override);
                getTrackSelector().setParameters(parametersBuilder);
            }
        }

    }

    public void setOnTimedTextListener(Player.Listener listener) {
        mMediaPlayer.addListener(listener);
    }

    // ── KernelTrackSupport（⑥ 适配层：PlayerTrackHelper 不再 instanceof 本类）──

    @Override
    public void selectTrack(@Nullable TrackInfoBean videoTrackBean) {
        if (videoTrackBean == null) {
            // 与旧 helper 一致:null bean 直接忽略(保留本内核 selectExoTrack(null)=清字幕的底层能力)
            return;
        }
        selectExoTrack(videoTrackBean);
    }

    @Override
    public boolean requiresControllerProgressRestart() {
        return true; // Exo 切换轨道后需 startProgress 恢复
    }

    @Override
    public void setOnSubtitleListener(PlayerTrackHelper.SubtitleListener listener) {
        if (listener == null) return;
        setOnTimedTextListener(new Player.Listener() {
            @Override
            public void onCues(@androidx.annotation.NonNull List<com.google.android.exoplayer2.text.Cue> cues) {
                try {
                    if (cues.size() > 0 && cues.get(0).text != null) {
                        listener.onSubtitle(cues.get(0).text.toString());
                    } else {
                        listener.onSubtitle(null);
                    }
                } catch (Throwable th) {
                    android.util.Log.w("PlayerTrackHelper", "Exo cue 回调异常: " + th.getMessage());
                }
            }
        });
    }
}
