// IRcloneService.aidl
package com.cc.libackupnext.service;

interface IRcloneService {

  /**
   * 列一层子目录（本地 / 远程共用），可先在该层建一个文件夹。
   *
   * @param request JSON：{lib, fs, path, create?, fixup?}。fixup 是本地外部存储里
   *   新建目录的绝对路径，仅列目录成功时由 root 侧修正归属；远程请求不带它
   * @return JSON：{ok:true, dirs:[名称...]} 或 {ok:false, error:"..."}
   */
  String listDirs(String request);

  /**
   * 起（或复用）一个只监听 127.0.0.1 的 serve restic，返回仓库地址。
   *
   * @param request JSON：{lib, fs, key}。key 同时包含仓库位置与 inline 配置，
   *   配置变化时会先停掉旧服务再起新的；URL 只在本次会话内有效，不落盘
   * @return JSON：{ok:true, url:"http://..."} 或 {ok:false, error:"..."}
   */
  String ensureServe(String request);

  /**
   * 读 rclone 的传输统计（`core/stats` 累计值）；调用方自己减基线得本次传输量。
   *
   * @return JSON：{ok:true, bytes:N, speed:S} 或 {ok:false, error:"..."}
   */
  String transferStats();

  /**
   * 读回全局超时配置，确认 `options/set` 生效（rclone 对不认识的字段静默忽略）。
   *
   * @return JSON：{ok:true, timeout:"30s", contimeout:"10s", low_level_retries:0}
   */
  String configReport();
}
