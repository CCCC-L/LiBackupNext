// IRusticService.aidl
package com.cc.libackupnext.service;

import com.cc.libackupnext.service.IBackupProgressCallback;


interface IRusticService {

  /**
   * 初始化 / 校验 rustic 仓库。
   *
   * - 目录为空：创建新仓库
   * - 目录已是 rustic 仓库：校验密码
   *
   * @return 成功返回空字符串；失败返回具体错误信息（可直接展示给用户）。
   */
  String initRepo(String path, String password);

  /**
   * 备份一个应用。
   *
   * @param request JSON：{repoPath, repoPassword, packageName, userId, apk, user, data, timestamp}
   * @param callback 进度回调，可为 null
   * @return 成功返回空字符串；失败返回具体错误信息
   */
  String backup(String request, IBackupProgressCallback callback);

  /**
   * 按 label 前缀（包名）查询快照。
   *
   * @param request JSON：{path, password, package_name}
   * @return JSON：{ok, snapshots:[{id,label,tags,time,description}], error}
   */
  String listSnapshots(String request);
  /** 每个包的备份概况（次数 + 最近一次备份），响应大小只跟应用数量有关。 */
  String overview(String request);
  /** 清理「备份已删光、图标快照还留着」的孤儿图标，返回清理数量。 */
  String cleanOrphanIcons(String request);

  /**
   * 修改一批快照的备注。
   *
   * @param request JSON：{path, password, ids:[...], description:"..."|null}
   * @return 成功返回空字符串；失败返回具体错误信息
   */
  String updateDescription(String request);

   /**
   * 把单个快照恢复到指定目录（导出图标这类小文件用）。
   *
   * @param request JSON：{path, password, id, dest}
   * @return 成功返回空字符串；失败返回原因
   */
  String exportSnapshot(String request);

  /**
   * 删除一批快照。
   *
   * @param request JSON：{path, password, ids:[...]}
   */
  String deleteSnapshots(String request);

  /**
   * 回收仓库空间（prune）：删掉没人引用的 pack。
   *
   * @param request JSON：{path, password}
   * @return 成功返回空字符串；失败返回具体错误信息
   */
  String prune(String request);

  /**
   * 只算不删：估算整理仓库能回收多少空间。
   *
   * @param request JSON：{path, password}
   * @return JSON：{ok:true, bytes:可回收字节数} 或 {ok:false, error:"..."}
   */
  String pruneEstimate(String request);

  /**
   * 恢复一批快照。
   *
   * @param request JSON：{path, password, userId, packageName, items:[{kind, id}]}
   */
  String restoreSnapshots(String request, IBackupProgressCallback callback);


}
