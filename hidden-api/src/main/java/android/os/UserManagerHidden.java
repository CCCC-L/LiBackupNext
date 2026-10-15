package android.os;

import android.content.Context;
import android.content.pm.UserInfo;
import dev.rikka.tools.refine.RefineAs;
import java.util.List;

@RefineAs(UserManager.class)
public class UserManagerHidden {

  public static UserManager get(Context context) {
    throw new RuntimeException("Stub!");
  }

  public List<UserInfo> getUsers() {
    throw new RuntimeException("Stub!");
  }


}
