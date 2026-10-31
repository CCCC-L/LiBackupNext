package android.app.usage;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(UsageStatsManager.class)
public class UsageStatsManagerHidden {

  public int getAppStandbyBucket(String packageName) {
    throw new RuntimeException("Stub!");
  }

  public void setAppStandbyBucket(String packageName, int bucket) {
    throw new RuntimeException("Stub!");
  }
}
