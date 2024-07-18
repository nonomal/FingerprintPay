package com.surcumference.fingerprint.network.update;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.hjq.toast.Toaster;
import com.surcumference.fingerprint.BuildConfig;
import com.surcumference.fingerprint.Constant;
import com.surcumference.fingerprint.Lang;
import com.surcumference.fingerprint.R;
import com.surcumference.fingerprint.bean.PluginTarget;
import com.surcumference.fingerprint.bean.PluginType;
import com.surcumference.fingerprint.bean.UpdateInfo;
import com.surcumference.fingerprint.network.inf.UpdateResultListener;
import com.surcumference.fingerprint.network.update.github.GithubUpdateChecker;
import com.surcumference.fingerprint.plugin.PluginApp;
import com.surcumference.fingerprint.util.ApplicationUtils;
import com.surcumference.fingerprint.util.Config;
import com.surcumference.fingerprint.util.FileUtils;
import com.surcumference.fingerprint.util.Task;
import com.surcumference.fingerprint.util.ZipUtils;
import com.surcumference.fingerprint.util.log.L;
import com.surcumference.fingerprint.view.DownloadView;
import com.surcumference.fingerprint.view.MagiskInstPluginTargetSelectionView;
import com.surcumference.fingerprint.view.MessageView;
import com.surcumference.fingerprint.view.ShellExecuteView;
import com.surcumference.fingerprint.view.UpdateInfoView;

import net.lingala.zip4j.exception.ZipException;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import eu.chainfire.libsuperuser.Shell;

/**
 * Created by Jason on 2017/9/10.
 */

public class UpdateFactory {

    public static void doUpdateCheck(final Context context) {
        doUpdateCheck(context, true, false);
    }

    public static void doUpdateCheck(final Context context, final boolean quite, final boolean dontSkip) {
        if (!quite) {
            Toaster.showLong(Lang.getString(R.id.toast_checking_update));
        }
        try {
            String packageName = context.getPackageName();
            String fileName = PluginApp.runActionBaseOnCurrentPluginType(new HashMap<PluginType, Callable<String>>() {{
                put(PluginType.Riru, () -> packageName + ".riru.zip");
                put(PluginType.Zygisk, () -> packageName + ".zygisk.zip");
                put(PluginType.Xposed, () -> packageName + ".apk");
            }});
            File targetFile = FileUtils.getSharableFile(context, fileName);
            FileUtils.delete(targetFile);
            new GithubUpdateChecker(BuildConfig.VERSION_NAME, Constant.UPDATE_URL_GITHUB,
                    new UpdateResultListener() {
                @Override
                public void onNoUpdate() {
                    if (!quite) {
                        Toaster.showLong(Lang.getString(R.id.toast_no_update));
                    }
                }

                @Override
                public void onNetErr(Exception e) {
                    if (!quite) {
                        Toaster.showLong(Lang.getString(R.id.toast_check_update_fail_net_err));
                    }
                }

                @Override
                public void onHasUpdate(UpdateInfo updateInfo) {
                    if (!dontSkip) {
                        if (isSkipVersion(context, updateInfo.version)) {
                            L.d("已跳過版本: " + updateInfo.version);
                            return;
                        }
                    }
                    Toaster.cancel();
                    UpdateInfoView updateInfoView = new UpdateInfoView(context);
                    updateInfoView.setTitle(Lang.getString(R.id.found_new_version) + updateInfo.version);
                    updateInfoView.setContent(updateInfo.content);
                    updateInfoView.withOnNeutralButtonClickListener((dialogInterface, i) -> {
                        Config.from(context).setSkipVersion(updateInfo.version);
                        dialogInterface.dismiss();
                    });
                    updateInfoView.withOnPositiveButtonClickListener((dialogInterface, i) -> {
                        PluginApp.runActionBaseOnCurrentPluginType(new HashMap<PluginType, Callable<Object>>() {{
                            put(PluginType.Riru, () -> {
                                handleMagiskUpdate(context, updateInfo, dialogInterface);
                                return null;
                            });
                            put(PluginType.Zygisk, () -> {
                                handleMagiskUpdate(context, updateInfo, dialogInterface);
                                return null;
                            });
                            put(PluginType.Xposed, () -> {
                                handleXposedUpdate(context, updateInfo, dialogInterface);
                                return null;
                            });
                        }});
                    });
                    Task.onMain(200, updateInfoView::showInDialog);
                }
            }).doUpdateCheck();
        } catch (Exception | Error e) {
            //for OPPO R11 Plus 6.0 NoSuchFieldError: No instance field mResultListener
            L.e(e);
        }
    }

    private static void handleMagiskUpdate(Context context, UpdateInfo updateInfo, DialogInterface updateInfoViewDialogInterface) {
        Task.onBackground(() -> {
            if (!Shell.SU.available()) {
                if (!Shell.SU.available()) {
                    Task.onMain(() -> new MessageView(context).text(Lang.getString(R.id.update_no_root)).showInDialog());
                    return;
                }
            }
            Task.onMain(() -> {
                MagiskInstPluginTargetSelectionView instPluginTargetSelectionView = new MagiskInstPluginTargetSelectionView(context);
                instPluginTargetSelectionView.showInDialog();
                instPluginTargetSelectionView.withOnPositiveButtonClickListener((dialog, which) -> {
                    Map<PluginTarget, Boolean> instPluginTargetSelectionMap = instPluginTargetSelectionView.getSelection();
                    L.d("instPluginTargetSelectionMap", instPluginTargetSelectionMap);
                    if (!instPluginTargetSelectionMap.values().contains(true)) {
                        Toaster.showShort(Lang.getString(R.id.update_at_least_select_one));
                        return;
                    }
                    dialog.dismiss();
                    String fileName = context.getPackageName() + ".zip";
                    File cacheDir = context.getCacheDir();
                    File targetFile = new File(cacheDir, fileName);
                    File unzipDir = new File(cacheDir, BuildConfig.APPLICATION_ID);
                    Runnable cleanTask = () -> {
                        FileUtils.delete(targetFile);
                        FileUtils.delete(unzipDir);
                    };
                    cleanTask.run();
                    String mirrorUrl = String.format(Locale.getDefault(), Constant.UPDATE_URL_MIRROR_FILE, updateInfo.version, updateInfo.name);
                    new DownloadView(context)
                            .download(new String[]{mirrorUrl, updateInfo.url}, targetFile, updateInfo.size, () -> {
                                    updateInfoViewDialogInterface.dismiss();
                                    ShellExecuteView shellExecuteView = new ShellExecuteView(context);
                                    shellExecuteView.showInDialog();
                                    Task.onBackground(() -> {
                                        try {
                                            unzipDir.mkdirs();
                                            try {
                                                ZipUtils.unzip(targetFile, unzipDir.getAbsolutePath(), "");
                                            } catch (ZipException e) {
                                                L.e(e);
                                                Task.onMain(() -> new MessageView(context).text(Lang.getString(R.id.update_file_corrupted)).showInDialog());
                                                return;
                                            }

                                            File[] moduleZipFiles = unzipDir.listFiles();
                                            Map<PluginTarget, File> moduleFilePluginTargetMap = matchMagiskModuleFileListToPluginTarget(moduleZipFiles);
                                            L.d("moduleFilePluginTargetMap", moduleFilePluginTargetMap);
                                            if (moduleFilePluginTargetMap.size() <= 0) {
                                                Task.onMain(() -> new MessageView(context).text(Lang.getString(R.id.update_file_missing)).showInDialog());
                                                return;
                                            }
                                            Iterator<Map.Entry<PluginTarget, Boolean>> it = instPluginTargetSelectionMap.entrySet().iterator();
                                            while (it.hasNext()) {
                                                Map.Entry<PluginTarget, Boolean> entry = it.next();
                                                PluginTarget pluginTarget = entry.getKey();
                                                boolean selected = (boolean)entry.getValue();
                                                if (!selected) {
                                                    continue;
                                                }
                                                File moduleFile = moduleFilePluginTargetMap.get(pluginTarget);
                                                if (moduleFile == null) {
                                                    continue;
                                                }
                                                String command = String.format("magisk --install-module \"%s\"", moduleFile.getAbsolutePath());
                                                int installModuleResult = shellExecuteView.executeCommand(command);
                                                if (installModuleResult != 0) {
                                                    Task.onMain(()-> shellExecuteView.appendCommandLineOutput(Lang.getString(R.id.update_installation_failed) + installModuleResult + "\n请尝试前往更新页面手动获取更新"));
                                                    return;
                                                }
                                            }
                                            Task.onMain(()-> shellExecuteView.appendCommandLineOutput(Lang.getString(R.id.update_success_note)));
                                        } finally {
                                            cleanTask.run();
                                        }
                                    });
                            }).showInDialog();

                });
            });
        });
    }

    private static Map<PluginTarget, File> matchMagiskModuleFileListToPluginTarget(@Nullable File[] moduleZipFiles) {
        Map<PluginTarget, File> map = new HashMap<>();
        if (moduleZipFiles == null) {
            return map;
        }
        PluginApp.iterateAllPluginTarget(pluginTarget -> {
            for (File file : moduleZipFiles) {
                if (file.getName().contains(pluginTarget.name().toLowerCase())) {
                    map.put(pluginTarget, file);
                    return;
                }
            }
        });
        return map;
    }

    private static void handleXposedUpdate(Context context, UpdateInfo updateInfo, DialogInterface updateInfoViewDialogInterface) {
        String fileName = context.getPackageName() + ".apk";
        File targetFile = FileUtils.getSharableFile(context, fileName);
        FileUtils.delete(targetFile);
        String mirrorUrl = String.format(Locale.getDefault(), Constant.UPDATE_URL_MIRROR_FILE, updateInfo.version, updateInfo.name);
        new DownloadView(context)
                .download(new String[]{mirrorUrl, updateInfo.url}, targetFile, updateInfo.size, () -> {
                    updateInfoViewDialogInterface.dismiss();
                    UpdateFactory.installApk(context, targetFile);
                    new MessageView(context).text(Lang.getString(R.id.update_success_note)).showInDialog();
                }).showInDialog();
    }

    public static void lazyUpdateWhenActivityAlive() {
        int lazyCheckTimeMsec = BuildConfig.DEBUG ? 200 : 6000;
        Task.onMain(lazyCheckTimeMsec, new Runnable() {
            @Override
            public void run() {
                Activity activity = ApplicationUtils.getCurrentActivity();
                if (activity == null
                    || activity.getClass().getName().contains("com.tencent.mm.app.WeChatSplashActivity")) {
                    Task.onMain(lazyCheckTimeMsec, this);
                    return;
                }
                UpdateFactory.doUpdateCheck(activity);
            }
        });
    }

    private static boolean isSkipVersion(Context context, String targetVersion) {
        Config config = Config.from(context);
        String skipVersion = config.getSkipVersion();
        if (TextUtils.isEmpty(skipVersion)) {
            return false;
        }
        if (String.valueOf(targetVersion).equals(skipVersion)) {
            return true;
        }
        return false;
    }

    public static void installApk(Context context, File file) {
        Uri uri = FileUtils.getUri(context, file);
        file.setReadable(true, false);
        file.getParentFile().setReadable(true, false);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        Task.onMain(() -> context.startActivity(intent));
    }
}
