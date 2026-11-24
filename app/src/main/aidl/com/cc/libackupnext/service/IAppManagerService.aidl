// IAppManagerService.aidl
package com.cc.libackupnext.service;


// Declare any non-default types here with import statements

interface IAppManagerService {

  List<UserInfo> getUsers();

  ParcelFileDescriptor getInstalledAppsAsUser(int userId);

  /**
   * 按需取单个应用图标（WEBP 字节）。用于主进程拿不到图标的情况：
   * 跨用户的应用、以及被 hide / 对普通应用不可见的包（例如 KernelSU 管理器）。
   */
  byte[] getIcon(String packageName, int userId);

  void forceStop(String packageName, int userId);

  /**
   * 暂停应用：给它在这个用户下的所有进程发 SIGSTOP，备份期间文件不会再被改写。
   *
   * @return 是否有进程真的被暂停（应用没在跑就是 false）
   */
  boolean pauseApp(String packageName, int userId);

  /** 取消暂停：SIGCONT。 */
  void resumeApp(String packageName, int userId);

  void freeze(String packageName, int userId);

  void unFreeze(String packageName, int userId);

  void clearAppData(String packageName, int userId);

  void unInstall(String packageName, int userId);

  /**
   * 批量判断目录是否存在。**传物理路径**（`/data/media/<userId>/…`）——
   * 主进程只看得见自己用户的空间，别的用户的目录只能由 root 判。
   *
   * @param request JSON：{paths:["/data/media/10/DCIM", ...]}
   * @return JSON：{ok:true, existing:[确实存在的那些]}
   */
  String statDirs(String request);

  /**
   * 打开 / 关闭 Wi-Fi。普通应用从 Android 10 起就没这个权限了，只能由 root 侧代劳。
   *
   * @return 成功返回空字符串；失败返回具体原因
   */
  String setWifiEnabled(boolean enabled);
}
