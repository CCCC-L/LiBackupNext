package android.app;

import dev.rikka.tools.refine.RefineAs;
import java.util.List;

@RefineAs(AppOpsManager.class)
public class AppOpsManagerHidden {

  public static class PackageOps {

    public List<OpEntry> getOps() {
      throw new RuntimeException("Stub!");
    }
  }

  public static class OpEntry {

    public int getOp() {
      throw new RuntimeException("Stub!");
    }

    public int getMode() {
      throw new RuntimeException("Stub!");
    }
  }

  /** 包级 op 模式；{@code ops} 传 null 表示全部。 */
  public List<PackageOps> getOpsForPackage(int uid, String packageName, int[] ops) {
    throw new RuntimeException("Stub!");
  }

  /** 写包级模式。 */
  public void setMode(int op, int uid, String packageName, int mode) {
    throw new RuntimeException("Stub!");
  }

  /** 写 uid 级模式。 */
  public void setUidMode(int op, int uid, int mode) {
    throw new RuntimeException("Stub!");
  }
}
