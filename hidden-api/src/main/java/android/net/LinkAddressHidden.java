package android.net;

import dev.rikka.tools.refine.RefineAs;

/**
 * 隐藏 API 存根：`LinkAddress(String)` 这个构造函数不在 SDK 里
 * （SDK 只公开了 getter 和 CREATOR）。
 *
 * 传 `192.168.1.100/24` 这种带前缀长度的写法，解析由系统负责。
 */
@RefineAs(LinkAddress.class)
public class LinkAddressHidden {

  public LinkAddressHidden(String address) {
    throw new RuntimeException("Stub!");
  }
}
