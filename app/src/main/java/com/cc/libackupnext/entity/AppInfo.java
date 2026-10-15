package com.cc.libackupnext.entity;

import android.graphics.drawable.Drawable;

/**
 * @author cong
 * @date 2026/10/15
 */
public record AppInfo(
    String name,
    String packageName,
    String version,
    Long versionCode,
    long installTime,
    long updateTime,
    Drawable icon,
    boolean isSystem
) {

}
