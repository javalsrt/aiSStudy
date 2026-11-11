package com.znxsgl.student;

import android.app.Application;

import com.znxsgl.student.widget.SquircleInstaller;

/**
 * 全局初始化：安装 G2 连续曲率圆角。
 * 字体继续使用项目内置的 HarmonyOS Sans SC（苹方/SF Pro 需另行提供字体文件）。
 */
public class StudentApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SquircleInstaller.install(this);
    }
}
