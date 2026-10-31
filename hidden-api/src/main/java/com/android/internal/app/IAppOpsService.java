package com.android.internal.app;

import android.app.AppOpsManagerHidden.PackageOps;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import java.util.List;

public interface IAppOpsService extends IInterface {

  List<PackageOps> getUidOps(int uid, int[] ops) throws RemoteException;

  abstract class Stub extends Binder implements IAppOpsService {

    public static IAppOpsService asInterface(IBinder binder) {
      throw new RuntimeException("Stub!");
    }
  }
}
