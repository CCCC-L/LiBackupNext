package android.app;

import android.content.pm.IPackageDataObserver;
import dev.rikka.tools.refine.RefineAs;

/**
 * 隐藏 API 存根，运行时由 Refine 强制转换到真实的 ActivityManager。
 */
@RefineAs(ActivityManager.class)
public class ActivityManagerHidden {

  /** 强制停止指定用户下的应用。 */
  public void forceStopPackageAsUser(String packageName, int userId) {
    throw new RuntimeException("Stub!");
  }

  /** 清空应用数据（作用于当前 ActivityManager 实例所属用户）。 */
  public boolean clearApplicationUserData(String packageName, IPackageDataObserver observer) {
    throw new RuntimeException("Stub!");
  }
}
