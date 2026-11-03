package android.net;

import dev.rikka.tools.refine.RefineAs;

/**
 * 隐藏 API 存根：`IpConfiguration` 的构造函数和 setter 都不在 SDK 里。
 *
 * SDK 里只有一个公开的 `CREATOR`，所以以前只能靠 Parcel 序列化来造对象。
 */
@RefineAs(IpConfiguration.class)
public class IpConfigurationHidden {

  public IpConfigurationHidden() {
    throw new RuntimeException("Stub!");
  }

  public void setIpAssignment(IpConfiguration$IpAssignment assignment) {
    throw new RuntimeException("Stub!");
  }

  public void setStaticIpConfiguration(StaticIpConfigurationHidden config) {
    throw new RuntimeException("Stub!");
  }
}
