/*
 * Copyright 2014-2017 Eduard Ereza Martínez
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cat.ereza.customactivityoncrash.activity;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

import cat.ereza.customactivityoncrash.CrashPageTheme;
import cat.ereza.customactivityoncrash.CustomActivityOnCrash;
import com.angcyo.tablayout.R;   // 第三方归堆后资源在本模块(:thirdparty),R 用本模块的
import cat.ereza.customactivityoncrash.config.CaocConfig;

public final class DefaultErrorActivity extends AppCompatActivity {

    @SuppressLint("PrivateResource")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //This is needed to avoid a crash if the developer has not specified
        //an app-level theme that extends Theme.AppCompat
        TypedArray a = obtainStyledAttributes(R.styleable.AppCompatTheme);
        if (!a.hasValue(R.styleable.AppCompatTheme_windowActionBar)) {
            setTheme(R.style.Theme_AppCompat_Light_DarkActionBar);
        }
        a.recycle();

        setContentView(R.layout.customactivityoncrash_default_error_activity);

        //Close/restart button logic:
        //If a class if set, use restart.
        //Else, use close and just finish the app.
        //It is recommended that you follow this logic if implementing a custom error activity.
        Button restartButton = findViewById(R.id.customactivityoncrash_error_activity_restart_button);

        final CaocConfig config = CustomActivityOnCrash.getConfigFromIntent(getIntent());

        if (config == null) {
            //This should never happen - Just finish the activity to avoid a recursive crash.
            finish();
            return;
        }

        if (config.isShowRestartButton() && config.getRestartActivityClass() != null) {
            restartButton.setText(R.string.customactivityoncrash_error_activity_restart_app);
            restartButton.setOnClickListener(v -> CustomActivityOnCrash.restartApplication(DefaultErrorActivity.this, config));
        } else {
            restartButton.setOnClickListener(v -> CustomActivityOnCrash.closeApplication(DefaultErrorActivity.this, config));
        }

        Button moreInfoButton = findViewById(R.id.customactivityoncrash_error_activity_more_info_button);

        if (config.isShowErrorDetails()) {
            moreInfoButton.setOnClickListener(v -> {
                //We retrieve all the error data and show it
                AlertDialog dialog = new AlertDialog.Builder(DefaultErrorActivity.this)
                        .setTitle(R.string.customactivityoncrash_error_activity_error_details_title)
                        .setMessage(CustomActivityOnCrash.getAllErrorDetailsFromIntent(DefaultErrorActivity.this, getIntent()))
                        .setPositiveButton(R.string.customactivityoncrash_error_activity_error_details_close, null)
                        .setNeutralButton(R.string.customactivityoncrash_error_activity_error_details_copy,
                                (dialog1, which) -> copyErrorToClipboard())
                        .show();
                // 让弹窗跟随当前生效主题(与崩溃页主体同源投影值);没有投影时保持系统默认外观
                CrashPageTheme.applyToDialog(DefaultErrorActivity.this, getIntent(), dialog);
                TextView textView = dialog.findViewById(android.R.id.message);
                if (textView != null) {
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.customactivityoncrash_error_activity_error_details_text_size));
                }
            });
        } else {
            moreInfoButton.setVisibility(View.GONE);
        }

        Integer defaultErrorActivityDrawableId = config.getErrorDrawable();
        ImageView errorImageView = findViewById(R.id.customactivityoncrash_error_activity_image);

        if (defaultErrorActivityDrawableId != null) {
            errorImageView.setImageDrawable(ResourcesCompat.getDrawable(getResources(), defaultErrorActivityDrawableId, getTheme()));
        }

        // 跟随用户当前生效的主题:主进程在主题生效时把语义色投影到约定的独立 SharedPreferences
        // (见 CrashPageTheme),本进程(:error_activity)只做一次极轻量读取 —— 不装 ThemeRuntime/Room,
        // 保持"错误页进程最小化启动"的设计。没有投影值时什么都不做,外观与不接这能力时一致。
        View themeRoot = findViewById(R.id.customactivityoncrash_error_activity_root);
        TextView themeMessage = findViewById(R.id.customactivityoncrash_error_activity_explanation);
        CrashPageTheme.apply(this, getIntent(), themeRoot, themeMessage, restartButton, moreInfoButton);
    }

    private void copyErrorToClipboard() {
        String errorInformation = CustomActivityOnCrash.getAllErrorDetailsFromIntent(DefaultErrorActivity.this, getIntent());

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        //Are there any devices without clipboard...?
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText(getString(R.string.customactivityoncrash_error_activity_error_details_clipboard_label), errorInformation);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(DefaultErrorActivity.this, R.string.customactivityoncrash_error_activity_error_details_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
