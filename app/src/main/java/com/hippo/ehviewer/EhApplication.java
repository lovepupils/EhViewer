/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Debug;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.LruCache;
import android.util.Log;

import com.github.anrwatchdog.ANRError;
import com.github.anrwatchdog.ANRWatchDog;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.conaco.Conaco;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.ui.CommonOperations;
import com.hippo.network.StatusCodeException;
import com.hippo.okhttp.CookieDB;
import com.hippo.scene.SceneApplication;
import com.hippo.text.Html;
import com.hippo.unifile.UniFile;
import com.hippo.util.BitmapUtils;
import com.hippo.util.ReadableTime;
import com.hippo.yorozuya.FileUtils;
import com.hippo.yorozuya.IntIdGenerator;
import com.hippo.yorozuya.SimpleHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class EhApplication extends SceneApplication implements Thread.UncaughtExceptionHandler {

    private static final String TAG = EhApplication.class.getSimpleName();

    public static final boolean BETA = false;

    private static final boolean DEBUG_CONACO = false;
    private static final boolean DEBUG_NATIVE_MEMORY = false;

    private Thread.UncaughtExceptionHandler mDefaultHandler;

    private final IntIdGenerator mIdGenerator = new IntIdGenerator();
    private final WeakHashMap<Integer, Object> mGlobalStuffMap = new WeakHashMap<>();
    private EhCookieStore mEhCookieStore;
    private EhClient mEhClient;
    private OkHttpClient mOkHttpClient;
    private BitmapHelper mBitmapHelper;
    private Conaco<Bitmap> mConaco;
    private LruCache<Long, GalleryDetail> mGalleryDetailCache;
    private SimpleDiskCache mSpiderInfoCache;
    private DownloadManager mDownloadManager;

    private final List<Activity> mActivityList = new ArrayList<>();

    @Override
    public void onCreate() {
        // Prepare to catch crash
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);

        // Start anr watch dog
        new ANRWatchDog().setANRListener(new ANRWatchDog.ANRListener() {
            @Override
            public void onAppNotResponding(ANRError error) {
                // Throw RuntimeException, let crash handler do it
                throw new RuntimeException(error.getCause());
            }
        }).start();

        super.onCreate();

        GetText.initialize(this);
        CookieDB.initialize(this);
        StatusCodeException.initialize(this);
        Settings.initialize(this);
        ReadableTime.initialize(this);
        Html.initialize(this);
        AppConfig.initialize(this);
        SpiderDen.initialize(this);
        EhDB.initialize(this);
        EhEngine.initialize();
        BitmapUtils.initialize(this);

        if (EhDB.needMerge()) {
            EhDB.mergeOldDB(this);
        }

        if (Settings.getEnableAnalytics()) {
            Analytics.start(this);
        }

        // Check no media file
        UniFile downloadLocation = Settings.getDownloadLocation();
        if (Settings.getMediaScan()) {
            CommonOperations.removeNoMediaFile(downloadLocation);
        } else {
            CommonOperations.ensureNoMediaFile(downloadLocation);
        }

        // Clear temp dir
        clearTempDir();

        // Check app update
        update();

        // Update version code
        try {
            PackageInfo pi= getPackageManager().getPackageInfo(getPackageName(), 0);
            Settings.putVersionCode(pi.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            // Ignore
        }

        if (DEBUG_NATIVE_MEMORY) {
            debugNativeMemory();
        }
    }

    private void clearTempDir() {
        File dir = AppConfig.getTempDir();
        if (null != dir) {
            FileUtils.deleteContent(dir);
        }
        dir = AppConfig.getExternalTempDir();
        if (null != dir) {
            FileUtils.deleteContent(dir);
        }
    }

    private void update() {
        int version = Settings.getVersionCode();
        if (version < 52) {
            Settings.putGuideGallery(true);
        }
        if (version < 56) { // Make cookie long live
            HttpUrl eUrl = HttpUrl.parse(EhUrl.HOST_E);
            HttpUrl exUrl = HttpUrl.parse(EhUrl.HOST_EX);
            EhCookieStore cookieStore = getEhCookieStore(this);

            Cookie c;
            c = cookieStore.get(eUrl, EhCookieStore.KEY_IPD_MEMBER_ID);
            if (null != c) {
                cookieStore.add(EhCookieStore.newCookie(c, c.domain(), true, true, true));
            }
            c = cookieStore.get(eUrl, EhCookieStore.KEY_IPD_PASS_HASH);
            if (null != c) {
                cookieStore.add(EhCookieStore.newCookie(c, c.domain(), true, true, true));
            }
            c = cookieStore.get(exUrl, EhCookieStore.KEY_IPD_MEMBER_ID);
            if (null != c) {
                cookieStore.add(EhCookieStore.newCookie(c, c.domain(), true, true, true));
            }
            c = cookieStore.get(exUrl, EhCookieStore.KEY_IPD_PASS_HASH);
            if (null != c) {
                cookieStore.add(EhCookieStore.newCookie(c, c.domain(), true, true, true));
            }
        }
    }

    public void clearMemoryCache() {
        if (null != mConaco) {
            mConaco.clearMemoryCache();
        }
        if (null != mGalleryDetailCache) {
            mGalleryDetailCache.evictAll();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            clearMemoryCache();
        }
    }

    private void debugNativeMemory() {
        new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Native memory: " + FileUtils.humanReadableByteCount(
                        Debug.getNativeHeapAllocatedSize(), false));
                SimpleHandler.getInstance().postDelayed(this, 3000);
            }
        }.run();
    }

    public int putGlobalStuff(@NonNull Object o) {
        int id = mIdGenerator.nextId();
        mGlobalStuffMap.put(id, o);
        return id;
    }

    public boolean containGlobalStuff(int id) {
        return mGlobalStuffMap.containsKey(id);
    }

    public Object getGlobalStuff(int id) {
        return mGlobalStuffMap.get(id);
    }

    public Object removeGlobalStuff(int id) {
        return mGlobalStuffMap.remove(id);
    }

    public boolean removeGlobalStuff(Object o) {
        return mGlobalStuffMap.values().removeAll(Collections.singleton(o));
    }

    public static EhCookieStore getEhCookieStore(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.mEhCookieStore == null) {
            application.mEhCookieStore = new EhCookieStore();
        }
        return application.mEhCookieStore;
    }

    @NonNull
    public static EhClient getEhClient(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.mEhClient == null) {
            application.mEhClient = new EhClient(application);
        }
        return application.mEhClient;
    }

    @NonNull
    public static OkHttpClient getOkHttpClient(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.mOkHttpClient == null) {
            application.mOkHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .cookieJar(getEhCookieStore(application))
                    .build();
        }
        return application.mOkHttpClient;
    }

    @NonNull
    public static BitmapHelper getBitmapHelper(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.mBitmapHelper == null) {
            application.mBitmapHelper = new BitmapHelper();
        }
        return application.mBitmapHelper;
    }

    private static int getMemoryCacheMaxSize(Context context) {
        final ActivityManager activityManager = (ActivityManager) context.
                getSystemService(Context.ACTIVITY_SERVICE);
        return Math.min(20 * 1024 * 1024,
                Math.round(0.2f * activityManager.getMemoryClass() * 1024 * 1024));
    }

    @NonNull
    public static Conaco<Bitmap> getConaco(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.mConaco == null) {
            Conaco.Builder<Bitmap> builder = new Conaco.Builder<>();
            builder.hasMemoryCache = true;
            builder.memoryCacheMaxSize = getMemoryCacheMaxSize(context);
            builder.hasDiskCache = true;
            builder.diskCacheDir = new File(context.getCacheDir(), "thumb");
            builder.diskCacheMaxSize = 80 * 1024 * 1024; // 80MB
            builder.okHttpClient = getOkHttpClient(context);
            builder.objectHelper = getBitmapHelper(context);
            builder.debug = DEBUG_CONACO;
            application.mConaco = builder.build();
        }
        return application.mConaco;
    }

    @NonNull
    public static LruCache<Long, GalleryDetail> getGalleryDetailCache(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.mGalleryDetailCache == null) {
            // Max size 25, 3 min timeout
            application.mGalleryDetailCache = new LruCache<>(25);
        }
        return application.mGalleryDetailCache;
    }

    @NonNull
    public static SimpleDiskCache getSpiderInfoCache(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (null == application.mSpiderInfoCache) {
            application.mSpiderInfoCache = new SimpleDiskCache(
                    new File(context.getCacheDir(), "spider_info"), 5 * 1024 * 1024); // 5M
        }
        return application.mSpiderInfoCache;
    }

    @NonNull
    public static DownloadManager getDownloadManager(@NonNull Context context) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.mDownloadManager == null) {
            application.mDownloadManager = new DownloadManager(application);
        }
        return application.mDownloadManager;
    }

    private boolean handleException(Throwable ex) {
        if (ex == null) {
            return false;
        }
        try {
            ex.printStackTrace();
            Crash.saveCrashInfo2File(this, ex);
            return true;
        } catch (Throwable tr) {
            return false;
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        if (!handleException(ex) && mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(thread, ex);
        }

        Activity activity = getTopActivity();
        if (activity != null) {
            activity.finish();
        }

        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(1);
    }

    @NonNull
    public static String getDeveloperEmail() {
        return "ehviewersu$gmail.com".replace('$', '@');
    }

    public void registerActivity(Activity activity) {
        mActivityList.add(activity);
    }

    public void unregisterActivity(Activity activity) {
        mActivityList.remove(activity);
    }

    @Nullable
    public Activity getTopActivity() {
        if (!mActivityList.isEmpty()) {
            return mActivityList.get(mActivityList.size() - 1);
        } else {
            return null;
        }
    }
}
