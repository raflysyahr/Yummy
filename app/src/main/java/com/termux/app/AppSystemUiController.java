package com.termux.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** Applies the user's status-bar visibility preference to every Activity in Yummy. */
public final class AppSystemUiController {

    private static final String PREFS_NAME = "yummy_editor_settings";
    private static final String KEY_HIDE_STATUS_BAR = "hide_status_bar";
    private static final boolean DEFAULT_HIDE_STATUS_BAR = true;

    private AppSystemUiController() {
    }

    public static boolean isStatusBarHidden(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_STATUS_BAR, DEFAULT_HIDE_STATUS_BAR);
    }

    /** Save the preference and apply it immediately to the Activity and optional Dialog window. */
    public static void setStatusBarHidden(Activity activity, Window additionalWindow,
                                          boolean hidden) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_STATUS_BAR, hidden)
            .apply();

        apply(activity.getWindow(), hidden);
        if (additionalWindow != null && additionalWindow != activity.getWindow()) {
            apply(additionalWindow, hidden);
        }
    }

    public static void apply(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        apply(activity.getWindow(), isStatusBarHidden(activity));
    }

    /** Apply status-bar visibility to one Activity or Dialog window. */
    public static void apply(Window window, boolean hidden) {
        if (window == null) return;

        if (hidden) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                if (hidden) {
                    controller.hide(WindowInsets.Type.statusBars());
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    controller.show(WindowInsets.Type.statusBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                }
            }
            return;
        }

        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        int statusBarFlags = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (hidden) {
            flags |= statusBarFlags;
        } else {
            flags &= ~statusBarFlags;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** Register once from Application so separate Yummy Activities share this preference. */
    public static void register(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                apply(activity);
                View decor = activity.getWindow().getDecorView();
                ViewTreeObserver observer = decor.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.addOnWindowFocusChangeListener(hasFocus -> {
                        if (hasFocus) apply(activity);
                    });
                }
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                apply(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }
}
