package android.net.wifi;

import android.net.IpConfiguration;
import dev.rikka.tools.refine.RefineAs;

/**
 * 隐藏 API 存根：`WifiConfiguration` 上公开 SDK 里没有的那个 getter。
 *
 * 静态 IP 存在 `IpConfiguration` 里，但 `WifiConfiguration` 只公开了 setter，
 * 读出来只能走这个隐藏方法。
 */
@RefineAs(WifiConfiguration.class)
public class WifiConfigurationHidden {

  public IpConfiguration getIpConfiguration() {
    throw new RuntimeException("Stub!");
  }
}
