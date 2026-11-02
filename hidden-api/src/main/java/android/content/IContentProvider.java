package android.content;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.IInterface;
import android.os.RemoteException;
import java.util.ArrayList;

/**
 * 隐藏 API 存根：provider 的 binder 接口，只声明这里用到的方法。
 *
 * 签名按 API 31+ 的形态写（参数是 `AttributionSource`）；API 30 上这一层还是
 * `query(String callingPkg, ...)`，所以这套通道只能跑在 31+。
 */
public interface IContentProvider extends IInterface {

  Cursor query(
      AttributionSource attributionSource,
      Uri uri,
      String[] projection,
      Bundle queryArgs,
      ICancellationSignal cancellationSignal)
      throws RemoteException;

  int bulkInsert(
      AttributionSource attributionSource,
      Uri uri,
      ContentValues[] values)
      throws RemoteException;

  ContentProviderResult[] applyBatch(
      AttributionSource attributionSource,
      String authority,
      ArrayList<ContentProviderOperation> operations)
      throws RemoteException;
}
