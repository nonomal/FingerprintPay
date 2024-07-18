package com.surcumference.fingerprint.network.update;

import com.surcumference.fingerprint.bean.UpdateInfo;
import com.surcumference.fingerprint.network.inf.IUpdateCheck;
import com.surcumference.fingerprint.network.inf.UpdateResultListener;
import com.surcumference.fingerprint.util.Task;

/**
 * Created by Jason on 2017/9/9.
 */

public abstract class BaseUpdateChecker implements IUpdateCheck, UpdateResultListener {

    private UpdateResultListener mResultListener;

    public BaseUpdateChecker(UpdateResultListener listener) {
        mResultListener = listener;
    }

    @Override
    public void onNoUpdate() {
        Task.onMain(() -> {
            UpdateResultListener listener = mResultListener;
            if (listener == null) {
                return;
            }
            listener.onNoUpdate();
        });
    }

    @Override
    public void onNetErr(Exception exception) {
        Task.onMain(() -> {
            UpdateResultListener listener = mResultListener;
            if (listener == null) {
                return;
            }
            listener.onNetErr(exception);
        });
    }

    @Override
    public void onHasUpdate(UpdateInfo updateInfo) {
        Task.onMain(() -> {
            UpdateResultListener listener = mResultListener;
            if (listener == null) {
                return;
            }
            listener.onHasUpdate(updateInfo);
        });
    }
}
