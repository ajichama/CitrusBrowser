/*
 * Copyright (C) 2017-2019 Hazuki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.hazuki.yuzubrowser.ui.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import jp.hazuki.yuzubrowser.core.utility.utils.ImageUtils;
import jp.hazuki.yuzubrowser.ui.ConstantsKt;
import jp.hazuki.yuzubrowser.ui.R;

public class PackageUtils {
    private PackageUtils() {
        throw new UnsupportedOperationException();
    }

    public static boolean isPermissionDerivedFromMyPackage(Context context, String permssion_name) {
        PackageManager pm = context.getPackageManager();
        try {
            return context.getApplicationInfo().packageName.equals(pm.getPermissionInfo(permssion_name, 0).packageName);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static Intent createChooser(Context context, String url, CharSequence title) {
        Intent query = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        return createChooser(context, query, title);
    }

    @SuppressWarnings("deprecation")
    public static Intent createChooser(Context context, Intent query, CharSequence title) {
        PackageManager manager = context.getPackageManager();
        List<ResolveInfo> infoList;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            infoList = manager.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));
        } else {
            infoList = manager.queryIntentActivities(query, PackageManager.MATCH_ALL);
        }

        String appId = context.getPackageName();
        List<ComponentName> excludeComponents = new ArrayList<>();
        for (ResolveInfo info : infoList) {
            if (appId.equals(info.activityInfo.packageName)) {
                excludeComponents.add(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
            }
        }

        Intent chooser = Intent.createChooser(query, title);
        if (!excludeComponents.isEmpty()) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excludeComponents.toArray(new ComponentName[0]));
        }
        return chooser;
    }

    public static void createShortcut(Context context, String title, String url, Bitmap favicon) {
        if (url != null && !TextUtils.isEmpty(title) && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Intent target = new Intent();
            target.setClassName(context, ConstantsKt.ACTIVITY_MAIN_BROWSER);
            target.setAction(Intent.ACTION_VIEW);
            target.setData(Uri.parse(url));

            IconCompat icon;

            if (favicon != null) {
                icon = IconCompat.createWithBitmap(ImageUtils.trimSquare(favicon, 192));
            } else {
                icon = IconCompat.createWithResource(context, R.mipmap.ic_link_shortcut);
            }

            ShortcutInfoCompat shortcutInfo = new ShortcutInfoCompat.Builder(context, Long.toString(System.currentTimeMillis()))
                    .setShortLabel(title)
                    .setIcon(icon)
                    .setIntent(target)
                    .build();

            ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null);
        } else {
            Toast.makeText(context, R.string.failed, Toast.LENGTH_SHORT).show();
        }
    }
}
