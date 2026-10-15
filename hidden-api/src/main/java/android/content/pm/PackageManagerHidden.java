package android.content.pm;

import android.content.IntentSender;
import android.graphics.drawable.Drawable;
import dev.rikka.tools.refine.RefineAs;
import java.util.List;

@RefineAs(PackageManager.class)
public class PackageManagerHidden {

  public List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
    throw new RuntimeException("Stub!");
  }

  public PackageInfo getPackageInfoAsUser(String packageName, int flags, int userId) {
    throw new RuntimeException("Stub!");
  }

  public CharSequence getApplicationLabel(ApplicationInfo info) {
    throw new RuntimeException("Stub!");
  }

  public Drawable getApplicationIcon(ApplicationInfo info) {
    throw new RuntimeException("Stub!");
  }

  /**
   * 启用/禁用（冻结/解冻）指定用户下的应用。
   * newState 取值：COMPONENT_ENABLED_STATE_DISABLED / COMPONENT_ENABLED_STATE_DEFAULT。
   */
  public void setApplicationEnabledSetting(String packageName, int newState, int flags, int userId) {
    throw new RuntimeException("Stub!");
  }

  /** 给指定用户启用已安装的应用（等价 pm install-existing --user）。 */
  public int installExistingPackageAsUser(String packageName, int userId, int installFlags,
      int installReason, IntentSender intentSender) {
    throw new RuntimeException("Stub!");
  }

  /** 卸载指定用户下的应用。 */
  public void deletePackageAsUser(String packageName, IPackageDeleteObserver observer, int flags,
      int userId) {
    throw new RuntimeException("Stub!");
  }
}
