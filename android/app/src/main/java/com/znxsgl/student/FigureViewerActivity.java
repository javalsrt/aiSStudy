package com.znxsgl.student;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

/**
 * 图形全屏查看：把题目里的图形放到整屏大小，解决手机上图形和标注看不清的问题。
 * 目前承载函数图像（FunctionPlotView），后续图片块可复用同一入口。
 */
public class FigureViewerActivity extends Activity {

    private static final String EXTRA_GRAPH = "graph";

    public static void open(Context context, String graph) {
        Intent i = new Intent(context, FigureViewerActivity.class);
        i.putExtra(EXTRA_GRAPH, graph);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_figure_viewer);
        getWindow().setStatusBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        String graph = getIntent().getStringExtra(EXTRA_GRAPH);
        com.znxsgl.student.widget.FunctionPlotView plot = findViewById(R.id.figure_plot);
        if (graph != null && !graph.trim().isEmpty()) {
            plot.setExpression(graph);
        }

        View.OnClickListener close = v -> finish();
        findViewById(R.id.btn_figure_close).setOnClickListener(close);
        plot.setOnClickListener(close);
    }
}
