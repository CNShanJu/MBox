package com.github.tvbox.osc.callback;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.airbnb.lottie.LottieAnimationView;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LoadingAnim;
import com.github.tvbox.osc.util.LoadingAnimFit;
import com.kingja.loadsir.callback.Callback;

/**
 * @author pj567
 * @date :2020/12/24
 * @description: 全局加载中回调(搜索/列表/详情等页面)。动画文件由设置页"加载动画"选项动态切换。
 */
public class LoadingCallback extends Callback {
    @Override
    protected int onCreateView() {
        return R.layout.view_loadsir_loading;
    }

    @Override
    protected void onViewCreate(Context context, View view) {
        super.onViewCreate(context, view);
        // 按设置页"加载动画"配置动态设置动画文件(默认 鱼/glowing_fish_loader,可选其他)
        LottieAnimationView animation = view.findViewById(R.id.lottie_loading);
        LoadingAnim.apply(animation);
        LoadingAnimFit.bindToSlot(animation, (ViewGroup) view, 8);
    }

    @Override
    public void onAttach(Context context, View view) {
        super.onAttach(context, view);
        // LoadSir 复用本回调视图:隐藏时从视图树摘除会 unschedule 并 cancel 掉 Lottie,
        // cancel 同时清掉 XML autoPlay,重新 attach 后不会自动续播(表现为加载中动画静止)。
        // 每次展示都重新 apply,保证动画起播,并顺带应用设置页的动画切换。
        LottieAnimationView animation = view.findViewById(R.id.lottie_loading);
        LoadingAnim.apply(animation);
        LoadingAnimFit.fitToSlot(animation, (ViewGroup) view, 8);
    }
}
