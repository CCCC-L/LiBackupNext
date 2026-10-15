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
}
