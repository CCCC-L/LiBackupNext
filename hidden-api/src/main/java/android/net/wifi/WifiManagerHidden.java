package android.net.wifi;

import java.util.List;

import dev.rikka.tools.refine.RefineAs;

/**
 * 隐藏 API 存根，运行时由 Refine 强制转换到真实的 WifiManager。
 *
 * 普通应用能拿到的 {@code getConfiguredNetworks()} 只返回「自己创建的」网络，
 * 拿不到已保存网络的密码；这个系统接口返回全部，且 PreSharedKey 是明文。
 */
@RefineAs(WifiManager.class)
public class WifiManagerHidden {

  /**
   * 全部已保存的网络（含密码）。
   *
   * 要求 READ_WIFI_CREDENTIAL，普通应用申请不到；root 进程（uid 0）在 PMS 那边直接放行。
   */
  public List<WifiConfiguration> getPrivilegedConfiguredNetworks() {
    throw new RuntimeException("Stub!");
  }
}
