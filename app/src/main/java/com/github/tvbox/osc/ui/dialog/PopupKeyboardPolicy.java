package com.github.tvbox.osc.ui.dialog;

import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.EditText;

import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.util.XPopupUtils;

import java.util.ArrayList;

/** 保持弹窗原有位置，输入法出现时只覆盖屏幕，不重新排布弹窗。 */
public final class PopupKeyboardPolicy {

    private PopupKeyboardPolicy() {
    }

    public static void onCreate(BasePopupView popup) {
        if (popup.popupInfo != null) {
            popup.popupInfo.isMoveUpToKeyboard = false;
        }
    }

    /** 在 XPopup 的焦点处理后调用；它会将带输入框的 view 模式窗口改成 adjustResize。 */
    public static void afterFocus(BasePopupView popup) {
        if (popup.popupInfo == null) return;
        // 同一个弹窗实例可能被新的 Builder 重绑，onCreate 只会在首次显示时执行。
        popup.popupInfo.isMoveUpToKeyboard = false;
        if (popup.popupInfo.isViewMode) {
            if (!popup.popupInfo.isRequestFocus) return;
            ArrayList<EditText> inputs = new ArrayList<>();
            XPopupUtils.findAllEditText(inputs, (ViewGroup) popup.getPopupContentView());
            if (inputs.isEmpty()) return;
        }
        Window window = popup.getHostWindow();
        if (window == null) return;
        int softInputMode = window.getAttributes().softInputMode;
        window.setSoftInputMode((softInputMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
    }
}
