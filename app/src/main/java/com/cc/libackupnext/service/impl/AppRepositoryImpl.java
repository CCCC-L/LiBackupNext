package com.cc.libackupnext.service.impl;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import com.cc.libackupnext.entity.AppInfo;
import com.cc.libackupnext.service.IAppRepository;
import java.util.List;

/**
 * @author cong
 * @date 2026/10/15
 */
public class AppRepositoryImpl implements IAppRepository {

  @Override
  public List<AppInfo> getInstalledApps(Context context, boolean showSystem) {
    var pm = context.getPackageManager();
    // List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
    var packages = pm.getInstalledApplications(
        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));

    return packages.parallelStream()
        // 系统应用
        .filter(pkg -> showSystem || (pkg.flags & ApplicationInfo.FLAG_SYSTEM) == 0)
        .map(pkg -> {
          var appName = pkg.loadLabel(pm).toString();
          var version = "";
          var installTime = 0L;
          var updateTime = 0L;
          var versionCode = 0L;

          try {
            var pInfo = pm.getPackageInfo(pkg.packageName, 0);
            version = pInfo.versionName;
            installTime = pInfo.firstInstallTime;
            updateTime = pInfo.lastUpdateTime;
            versionCode = pInfo.getLongVersionCode();
          } catch (NameNotFoundException ignored) {
          }

          return new AppInfo(appName, pkg.packageName, version, versionCode, installTime, updateTime,
              pkg.loadIcon(pm), (pkg.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        })
        .toList();
  }
}
