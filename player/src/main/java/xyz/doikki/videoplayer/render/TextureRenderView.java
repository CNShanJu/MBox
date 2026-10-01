package xyz.doikki.videoplayer.render;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xyz.doikki.videoplayer.player.AbstractPlayer;

@SuppressLint("ViewConstructor")
public class TextureRenderView extends TextureView implements IRenderView, TextureView.SurfaceTextureListener {
    private MeasureHelper mMeasureHelper;
    private SurfaceTexture mSurfaceTexture;

    @Nullable
    private AbstractPlayer mMediaPlayer;
    private Surface mSurface;

    public TextureRenderView(Context context) {
        super(context);
    }

    {
        mMeasureHelper = new MeasureHelper();
        setSurfaceTextureListener(this);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            requestLayout();
        }
    }

    @Override
    public void setVideoRotation(int degree) {
        mMeasureHelper.setVideoRotation(degree);
        setRotation(degree);
    }

    @Override
    public void setScaleType(int scaleType) {
        mMeasureHelper.setScreenScale(scaleType);
        requestLayout();
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public Bitmap doScreenShot() {
        return getBitmap();
    }

    @Override
    public void release() {
        // 解绑由宿主负责(VideoView.release/addDisplay 在释放渲染视图之前调 player.detachSurface()),
        // 这里只回收自己持有的 Surface/SurfaceTexture,并把字段置空 —— 不置空的话,重新 attach 后
        // onSurfaceTextureAvailable 会拿这块"已释放的 SurfaceTexture"再 setSurfaceTexture 一次,直接崩在 native。
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }

        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }

        mMediaPlayer = null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        if (mSurfaceTexture != null) {
            setSurfaceTexture(mSurfaceTexture);
        } else {
            // 纹理销毁时已解绑并清空引用(见 onSurfaceTextureDestroyed),这里拿到的是系统新建的那块:
            // 必须用新建的 Surface 交给内核,绝不能复用已释放的 SurfaceTexture(那会崩在 native)
            mSurfaceTexture = surfaceTexture;
            mSurface = new Surface(surfaceTexture);
            if (mMediaPlayer != null) {
                mMediaPlayer.setSurface(mSurface);
            }
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // 画面源要消失了:先让内核与它解绑,再让系统回收这块 SurfaceTexture,并把本地引用一起清掉
        // (下次 available 走"新建 Surface"分支,不复用旧的)。
        //
        // 原实现是 `return false`(留住纹理复用)且不解绑 —— 于是编解码器会继续往一块"消费端已摘掉"
        // 的 SurfaceTexture 上输出;重回前台再 setSurfaceTexture(旧纹理)时,队列里那几帧陈旧/撕裂的
        // 缓冲会被直接显示出来。真机表现(2026-10-01 用户反馈):**偶发整块画面花屏,强制返回退出
        // 播放页即恢复**,系统日志无任何报错、进程也不崩 —— 正是这条路径的典型特征。
        // surface 渲染那条路(SurfaceRenderView.surfaceDestroyed)本来就有 setDisplay(null) 解绑,
        // 这里补上同口径处理,两条渲染路径行为一致。
        if (mMediaPlayer != null) {
            mMediaPlayer.detachSurface();
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        mSurfaceTexture = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }
}