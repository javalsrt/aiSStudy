package com.znxsgl.student.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.znxsgl.student.R;

/**
 * 带 MetaBalls 动画的圆角加载对话框。
 */
public class LoadingDialog extends Dialog {

    private final String message;

    public LoadingDialog(@NonNull Context context, String message) {
        super(context, R.style.LoadingDialog);
        this.message = message;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_loading);

        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        setCancelable(false);
        setCanceledOnTouchOutside(false);

        TextView tvMessage = findViewById(R.id.tv_loading_message);
        if (message != null) {
            tvMessage.setText(message);
        }
    }
}
