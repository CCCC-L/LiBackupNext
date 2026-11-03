package android.net;

import dev.rikka.tools.refine.RefineAs;
import java.net.InetAddress;

/**
 * 隐藏 API 存根：`StaticIpConfiguration` 的构造函数和字段都不在 SDK 里
 * （SDK 只公开了四个 getter）。
 */
@RefineAs(StaticIpConfiguration.class)
public class StaticIpConfigurationHidden {

  public StaticIpConfigurationHidden() {
    throw new RuntimeException("Stub!");
  }

  public LinkAddress ipAddress;

  public InetAddress gateway;

  /** 真实字段是 final ArrayList，不能赋值，只能往里加。 */
  public void addDnsServer(InetAddress server) {
    throw new RuntimeException("Stub!");
  }

  public String domains;
}
