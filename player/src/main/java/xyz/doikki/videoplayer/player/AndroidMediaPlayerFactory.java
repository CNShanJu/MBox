package xyz.doikki.videoplayer.player;

import android.content.Context;

/**
 * 创建{@link AndroidMediaPlayer}的工厂类，不推荐，系统的MediaPlayer兼容性较差，建议使用Media3(ExoPlayer)
 */
public class AndroidMediaPlayerFactory extends PlayerFactory<AndroidMediaPlayer> {

    public static AndroidMediaPlayerFactory create() {
        return new AndroidMediaPlayerFactory();
    }

    @Override
    public AndroidMediaPlayer createPlayer(Context context) {
        return new AndroidMediaPlayer(context);
    }
}
