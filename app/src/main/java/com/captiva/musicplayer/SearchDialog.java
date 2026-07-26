package com.captiva.musicplayer;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 搜索对话框
 * 使用内置键盘,避免车机输入法问题
 */
public class SearchDialog extends Dialog {

    public interface OnSearchListener {
        void onSearch(String query);
    }

    private OnSearchListener listener;
    private SimpleKeyboardView keyboard;
    private TextView tvInput;
    private String hintText;
    private String initialText;

    public SearchDialog(Context context, String hintText, String initialText) {
        super(context);
        this.hintText = hintText;
        this.initialText = initialText;
    }

    public void setOnSearchListener(OnSearchListener l) {
        this.listener = l;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 构建布局
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getContext().getResources().getColor(R.color.bg_main));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        TextView tvTitle = new TextView(getContext());
        tvTitle.setText("搜索音乐");
        tvTitle.setTextColor(getContext().getResources().getColor(R.color.text_title));
        tvTitle.setTextSize(20);
        tvTitle.setPadding(24, 20, 24, 12);
        root.addView(tvTitle);

        // 输入显示框(只读,由内置键盘输入)
        tvInput = new EditText(getContext());
        tvInput.setTextSize(18);
        tvInput.setTextColor(getContext().getResources().getColor(R.color.text_primary));
        tvInput.setBackgroundColor(getContext().getResources().getColor(R.color.search_bg));
        tvInput.setHint(hintText != null ? hintText : "输入搜索内容...");
        tvInput.setTextColorHint(getContext().getResources().getColor(R.color.search_hint));
        tvInput.setSingleLine(true);
        tvInput.setPadding(24, 16, 24, 16);
        // 禁用系统输入法
        tvInput.setShowSoftInputOnFocus(false);
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            try {
                tvInput.setTextIsSelectable(true);
            } catch (Exception ignored) {}
        }
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(24, 0, 24, 8);
        root.addView(tvInput, inputLp);

        // 内置键盘
        keyboard = new SimpleKeyboardView(getContext());
        LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(keyboard, kbLp);

        if (initialText != null && !initialText.isEmpty()) {
            keyboard.setText(initialText);
            tvInput.setText(initialText);
        }

        keyboard.bindInputTextView(tvInput);
        keyboard.setOnTextChangedListener(new SimpleKeyboardView.OnTextChangedListener() {
            @Override
            public void onTextChanged(String text) {
                tvInput.setText(text);
            }
        });

        // 按钮栏
        LinearLayout btnRow = new LinearLayout(getContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(24, 8, 24, 16);

        Button btnCancel = new Button(getContext());
        btnCancel.setText("取消");
        btnCancel.setBackgroundResource(R.drawable.bg_btn);
        btnCancel.setTextColor(getContext().getResources().getColor(R.color.btn_text));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.setMargins(0, 0, 8, 0);
        btnCancel.setOnClickListener(v -> dismiss());
        btnRow.addView(btnCancel, cancelLp);

        Button btnSearch = new Button(getContext());
        btnSearch.setText("搜索");
        btnSearch.setBackgroundResource(R.drawable.bg_btn_play);
        btnSearch.setTextColor(getContext().getResources().getColor(R.color.btn_play_text));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnSearch.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSearch(keyboard.getText());
            }
            dismiss();
        });
        btnRow.addView(btnSearch, searchLp);

        root.addView(btnRow);

        setContentView(root);
        getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
