package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

/**
 * 使用 {@link SheetResizeController} 状态机(50%↔70%,≤30% 收起关闭)管理高度的底部弹窗基类。
 * <ul>
 *   <li>{@link #getMaxHeight()} 统一返回“无上限”(XPopup 不再按分档封顶,高度全由状态机管理);</li>
 *   <li>{@link #attachSheet(int, int, int, boolean)} 统一完成 attach → 动作监听 → 50% 占位
 *       → (可选)布局后自动 sync(按内容决定 自适应/可展开);</li>
 *   <li>子类通过 {@link #createSheetActionListener()} 注入动作回传(默认空实现),或自行再 setActionListener。</li>
 * </ul>
 * 布局结构要求:内容根为纵向 LinearLayout(bar/固定头 + flex 弹性区[list_box 或 ScrollView] + 可选底栏)。
 */
public abstract class SheetResizableBottomPopup extends AppBottomPopupView {

    private static final SheetResizeController.ActionListener EMPTY =
            new SheetResizeController.ActionListener() {
                @Override public void onSheetExpanded() { }
                @Override public void onSheetCollapsed() { }
                @Override public void onSheetClosed() { }
            };

    private SheetResizeController mSheet;

    protected SheetResizableBottomPopup(@NonNull Context context) {
        super(context);
    }

    /** 当前弹窗的拖拽状态机(可再 setActionListener / expand / collapse / sync) */
    protected final SheetResizeController sheetResize() {
        return mSheet;
    }

    /**
     * 绑定“root(纵向内容根) + flex(weight=1 弹性区) + content(测量用内容)”并进入收起态 50% 占位。
     *
     * @param autoSync true:布局完成后自动 sync(短内容→自适应,超高→可展开);需自行编排(如 loading→setData)
     *                 时传 false,自行在数据就绪后调用 sync。
     */
    protected final SheetResizeController attachSheet(@IdRes int rootId, @IdRes int flexId,
                                                      @IdRes int contentId, boolean autoSync) {
        mSheet = SheetResizeController.attach(this, findViewById(rootId),
                findViewById(flexId), findViewById(contentId));
        mSheet.setActionListener(createSheetActionListener());
        mSheet.applyDefault();
        if (autoSync) {
            final View content = findViewById(contentId);
            if (content != null) {
                content.post(() -> {
                    if (mSheet != null && isShow()) mSheet.sync();
                });
            }
        }
        return mSheet;
    }

    /** 拖拽动作回传(展开/收回/收起),子类按需覆写 */
    protected SheetResizeController.ActionListener createSheetActionListener() {
        return EMPTY;
    }

    /** 高度由 SheetResizeController 状态机管理,禁用 XPopup 分档封顶 */
    @Override
    protected int getMaxHeight() {
        return DialogHeightPolicy.HEIGHT_UNBOUNDED;
    }
}
