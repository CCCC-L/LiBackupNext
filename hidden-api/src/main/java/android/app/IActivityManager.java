package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * 隐藏 API 存根：只声明这里用到的方法。
 *
 * 编译期用这个接口，运行时由 boot classpath 提供真实的 `android.app.IActivityManager`，
 * 方法名和描述符对得上就能直接调到。
 */
public interface IActivityManager extends IInterface {

  /**
   * 取一个 provider 的 binder，不要求调用方是 AMS 里登记过的应用进程。
   *
   * @param token 外部客户端的标识，随便一个 IBinder
   * @param packageName 客户端包名（用于归因），可为 null
   */
  ContentProviderHolder getContentProviderExternal(
      String name, int userId, IBinder token, String packageName) throws RemoteException;

  /** 释放 [getContentProviderExternal] 借来的 provider 引用。 */
  void removeContentProviderExternalAsUser(String name, IBinder token, int userId)
      throws RemoteException;

  abstract class Stub extends Binder implements IActivityManager {

    public static IActivityManager asInterface(IBinder binder) {
      throw new RuntimeException("Stub!");
    }
  }
}
