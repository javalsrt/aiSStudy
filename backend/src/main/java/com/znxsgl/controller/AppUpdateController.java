package com.znxsgl.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App 版本检查接口（公开，无需登录）。
 *
 * 最新版本信息由 application.yml 的 app.update.* 配置提供；
 * 发布新版本时：修改 Android versionCode/versionName → 构建 APK → 替换官网 APK →
 * 同步修改这里的配置值（或通过环境变量 APP_UPDATE_* 覆盖）。
 */
@RestController
@RequestMapping("/api/app")
public class AppUpdateController {

    @Value("${app.update.version-code:0}")
    private int versionCode;

    @Value("${app.update.version-name:}")
    private String versionName;

    @Value("${app.update.download-url:}")
    private String downloadUrl;

    @Value("${app.update.changelog:}")
    private String changelog;

    @Value("${app.update.force:false}")
    private boolean forceUpdate;

    @GetMapping("/latest")
    public Map<String, Object> latest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionCode", versionCode);
        body.put("versionName", versionName);
        body.put("downloadUrl", downloadUrl);
        body.put("changelog", changelog);
        body.put("forceUpdate", forceUpdate);
        return body;
    }
}
