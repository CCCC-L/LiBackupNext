package android.content.pm;

import android.os.IInterface;

/**
 * 隐藏 API 存根：卸载结果回调。
 * 本项目只传 null，仅用于让编译期签名成立。
 */
public interface IPackageDeleteObserver extends IInterface {

  void packageDeleted(String packageName, int returnCode) throws android.os.RemoteException;
}
