package com.cc.libackupnext.service.util;

import android.net.IpConfiguration;
import android.net.IpConfigurationHidden;
import android.net.LinkAddress;
import android.net.LinkAddressHidden;
import android.net.StaticIpConfigurationHidden;

import java.net.InetAddress;
import java.util.List;

/**
 * 用可读字段拼一个静态 IP 的 {@link IpConfiguration}。
 *
 * 为什么是 Java：`IpConfiguration` / `StaticIpConfiguration` / `LinkAddress` 的构造函数
 * 都不在 SDK 里（运行时是 public），靠 hidden-api 里的 Refine 存根来调；而
 * `IpConfiguration.IpAssignment` 在 android.jar 里连嵌套类都不存在，只能写成
 * 带 `$` 的顶层类型名 —— Kotlin 的标识符不允许 `$`，所以这一小段放在 Java 里。
 *
 * 调用方记得先判断 `Build.VERSION.SDK_INT >= 33`：`setIpConfiguration` 是 33 才公开的。
 */
public final class StaticIpFactory {

  private StaticIpFactory() {
  }

  /**
   * @param address 形如 `192.168.1.100/24`
   * @return 拼不出来时返回 null
   */
  public static IpConfiguration build(
      String address, String gateway, List<String> dns, String domains) {
    try {
      StaticIpConfigurationHidden config = new StaticIpConfigurationHidden();
      if (address != null) {
        config.ipAddress = (LinkAddress) (Object) new LinkAddressHidden(address);
      }
      if (gateway != null) {
        config.gateway = InetAddress.getByName(gateway);
      }
      if (dns != null) {
        for (String server : dns) {
          config.addDnsServer(InetAddress.getByName(server));
        }
      }
      if (domains != null) {
        config.domains = domains;
      }

      IpConfigurationHidden ipConfig = new IpConfigurationHidden();
      ipConfig.setIpAssignment(android.net.IpConfiguration$IpAssignment.STATIC);
      ipConfig.setStaticIpConfiguration(config);
      return (IpConfiguration) (Object) ipConfig;
    } catch (Throwable t) {
      android.util.Log.e("StaticIpFactory", "build failed", t);
      return null;
    }
  }
}
