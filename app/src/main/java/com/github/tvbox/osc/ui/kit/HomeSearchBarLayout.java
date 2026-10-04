package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;

/** 首页来源按内容占宽；先为搜索入口和右侧动作保留空间，再限制来源名称。 */
public class HomeSearchBarLayout extends LinearLayout {

    private View sourceContainer;
    private TextView sourceName;
    private View search;

    public HomeSearchBarLayout(Context context) {
        this(context, null);
    }

    public HomeSearchBarLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomeSearchBarLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        sourceContainer = findViewById(R.id.nameContainer);
        sourceName = findViewById(R.id.tvName);
        search = findViewById(R.id.search);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int sourceMaxWidth = Integer.MAX_VALUE;
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int reservedWidth = getPaddingLeft() + getPaddingRight();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child == sourceContainer || child.getVisibility() == GONE) continue;
                // 搜索入口先按提示文字、图标、内边距和 minWidth 量自然宽度；
                // 正式测量时仍由 layout_weight 接收来源占位后的全部剩余空间。
                int childWidthSpec = child == search
                        ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        : widthMeasureSpec;
                measureChildWithMargins(child, childWidthSpec, 0, heightMeasureSpec, 0);
                reservedWidth += child.getMeasuredWidth() + horizontalMargins(child);
            }
            reservedWidth += sourceContainer.getPaddingLeft() + sourceContainer.getPaddingRight()
                    + horizontalMargins(sourceContainer) + horizontalMargins(sourceName);
            sourceMaxWidth = Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - reservedWidth);
        }
        // 每次测量都使用当前窗口宽度，切源、旋转或分屏后无需等一次布局回调再纠正。
        if (sourceName.getMaxWidth() != sourceMaxWidth) sourceName.setMaxWidth(sourceMaxWidth);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private static int horizontalMargins(View view) {
        MarginLayoutParams params = (MarginLayoutParams) view.getLayoutParams();
        return params.leftMargin + params.rightMargin;
    }
}
