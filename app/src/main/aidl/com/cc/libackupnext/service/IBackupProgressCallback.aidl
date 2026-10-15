// IBackupProgressCallback.aidl
package com.cc.libackupnext.service;

/** 备份进度回调，progress 是 JSON：{"stage":"apk","state":"done","files":12} */
interface IBackupProgressCallback {

  oneway void onProgress(String progress);

}
