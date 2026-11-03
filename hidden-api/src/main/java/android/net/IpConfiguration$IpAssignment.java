package android.net;

/**
 * 隐藏 API 存根：`IpConfiguration.IpAssignment` 这个嵌套枚举在 android.jar 里
 * 完全不存在，连 `IpConfiguration.IpAssignment.class` 都写不出来，所以只能用
 * 带 `$` 的顶层类型名 —— 编译出来的二进制名和真实的嵌套类完全一致，
 * 运行时由 boot classpath 提供真类，不需要 Refine 重写。
 */
public enum IpConfiguration$IpAssignment {
  DHCP,
  STATIC,
  UNASSIGNED
}
