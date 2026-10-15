package com.cc.libackupnext.service;

import android.content.Context;
import com.cc.libackupnext.entity.AppInfo;
import java.util.List;

/**
 * @author cong
 * @date 2026/10/15
 */
public interface IAppRepository {
  List<AppInfo> getInstalledApps(Context context, boolean showSystem);
}
