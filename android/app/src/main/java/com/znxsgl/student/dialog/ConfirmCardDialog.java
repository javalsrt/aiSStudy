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
 * 通用灰白渐变确认卡片弹窗。
 */
public class ConfirmCardDialog extends Dialog {

    public interface OnConfirmListener {
        void onConfirm();
    }

    private final String label;
    private final String title;
    private final String desc;
    private final String negativeText;
    private final String positiveText;
    private final OnConfirmListener listener;

    public ConfirmCardDialog(@NonNull Context context,
                             String label, String title, String desc,
                             String negativeText, String positiveText,
                             OnConfirmListener listener) {
        super(context, R.style.LoadingDialog);
        this.label = label;
        this.title = title;
        this.desc = desc;
        this.negativeText = negativeText;
        this.positiveText = positiveText;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_confirm_start);

        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        setCancelable(true);
        setCanceledOnTouchOutside(true);

        TextView tvLabel = findViewById(R.id.tv_label);
        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvDesc = findViewById(R.id.tv_desc);
        TextView btnNo = findViewById(R.id.btn_no);
        TextView btnYes = findViewById(R.id.btn_yes);

        tvLabel.setText(label);
        tvTitle.setText(title);
        if (desc != null && !desc.isEmpty()) {
            tvDesc.setText(desc);
            tvDesc.setVisibility(android.view.View.VISIBLE);
        } else {
            tvDesc.setVisibility(android.view.View.GONE);
        }
        btnNo.setText(negativeText);
        btnYes.setText(positiveText);

        btnNo.setOnClickListener(v -> dismiss());
        btnYes.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onConfirm();
        });
    }
}
