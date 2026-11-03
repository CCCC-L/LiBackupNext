// IPackageManager.aidl
package android.content.pm;

// Declare any non-default types here with import statements

interface IPackageManager {
     void setApplicationEnabledSetting(in String packageName, in int newState, int flags,
                int userId, String callingPackage);

     int getApplicationEnabledSetting(in String packageName, int userId);
}