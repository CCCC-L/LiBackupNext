package android.content.pm;

import android.os.IInterface;

/**
 * 隐藏 API 存根：清空应用数据的回调。
 * 本项目只传 null，仅用于让编译期签名成立。
 */
public interface IPackageDataObserver extends IInterface {

  void onRemoveCompleted(String packageName, boolean succeeded) throws android.os.RemoteException;
}
