package cn.aigestudio.downloader.bizs;


import android.content.Context;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.Callable;

import cn.aigestudio.downloader.utils.HttpsUtils;

import static cn.aigestudio.downloader.bizs.DLCons.Base.DEFAULT_TIMEOUT;
import static cn.aigestudio.downloader.bizs.DLCons.Base.LENGTH_PER_THREAD;
import static cn.aigestudio.downloader.bizs.DLCons.Base.MAX_REDIRECTS;
import static cn.aigestudio.downloader.bizs.DLCons.Code.HTTP_MOVED_PERM;
import static cn.aigestudio.downloader.bizs.DLCons.Code.HTTP_MOVED_TEMP;
import static cn.aigestudio.downloader.bizs.DLCons.Code.HTTP_NOT_MODIFIED;
import static cn.aigestudio.downloader.bizs.DLCons.Code.HTTP_OK;
import static cn.aigestudio.downloader.bizs.DLCons.Code.HTTP_PARTIAL;
import static cn.aigestudio.downloader.bizs.DLCons.Code.HTTP_SEE_OTHER;
import static cn.aigestudio.downloader.bizs.DLCons.Code.HTTP_TEMP_REDIRECT;
import static cn.aigestudio.downloader.bizs.DLError.ERROR_OPEN_CONNECT;

/**
 * 这是下载task
 */
class DLTask implements Runnable, IDLThreadListener {
    private static final String TAG = DLTask.class.getSimpleName();

    private DLInfo info;
    private Context context;

    private int totalProgress;
    private int count;//这是线程数
    private long lastTime = System.currentTimeMillis();

    DLTask(Context context, DLInfo info) {
        this.info = info;
        this.context = context;
        this.totalProgress = info.currentBytes;
        //如果是第一次，isResume就是false
        if (!info.isResume) DLDBManager.getInstance(context).insertTaskInfo(info);
    }

    @Override
    public synchronized void onProgress(int progress) {
        totalProgress += progress;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTime > 1000) {
            if (DLCons.DEBUG) {
                Log.d(TAG, totalProgress + "..info." + info.baseUrl);
            }
            if (info.hasListener) info.listener.onProgress(totalProgress);
            lastTime = currentTime;
        }
    }

    @Override
    public synchronized void onStop(DLThreadInfo threadInfo) {
        if (null == threadInfo) {
            //这是没有线程了的操作的暂停
            DLManager.getInstance(context).removeDLTask(info.baseUrl);
            DLDBManager.getInstance(context).deleteTaskInfo(info.baseUrl);
            if (info.hasListener) {
                info.listener.onProgress(info.totalBytes);
                info.listener.onStop(info.totalBytes);
            }
            //这里同时开启下一个线程的下载
            DLManager.getInstance(context).addDLTask();
            return;
        }
        DLDBManager.getInstance(context).updateThreadInfo(threadInfo);
        count++;
        if (count >= info.threads.size()) {
            if (DLCons.DEBUG) {
                Log.d(TAG, "All the threads was stopped.");
            }
            info.currentBytes = totalProgress;
            //这里会添加到暂停队列，并且移除下载中队列
            DLManager.getInstance(context).addStopTask(info).removeDLTask(info.baseUrl);
            //更新下载的信息
            DLDBManager.getInstance(context).updateTaskInfo(info);
            count = 0;
            if (info.hasListener) info.listener.onStop(totalProgress);
            //这里同时开启下一个线程的下载
            DLManager.getInstance(context).addDLTask();
        }
    }

    @Override
    public synchronized void onFinish(DLThreadInfo threadInfo) {
        if (null == threadInfo) {
            DLManager.getInstance(context).removeDLTask(info.baseUrl);
            DLDBManager.getInstance(context).deleteTaskInfo(info.baseUrl);
            if (info.hasListener) {
                info.listener.onProgress(info.totalBytes);
                info.listener.onFinish(info.file);
            }
            //这里同时开启下一个线程的下载
            DLManager.getInstance(context).addDLTask();
            return;
        }
        info.removeDLThread(threadInfo);
        DLDBManager.getInstance(context).deleteThreadInfo(threadInfo.id);
        if (DLCons.DEBUG) {
            Log.d(TAG, "Thread size " + info.threads.size());
        }
        if (info.threads.isEmpty()) {
            if (DLCons.DEBUG) {
                Log.d(TAG, "Task was finished.");
            }
            DLManager.getInstance(context).removeDLTask(info.baseUrl);
            DLDBManager.getInstance(context).deleteTaskInfo(info.baseUrl);
            if (info.hasListener) {
                info.listener.onProgress(info.totalBytes);
                info.listener.onFinish(info.file);
            }
            //这里同时开启下一个线程的下载
            DLManager.getInstance(context).addDLTask();
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        if (DLCons.DEBUG) {
            Log.d(TAG, "start down." + info.baseUrl);
        }
        while (info.redirect < MAX_REDIRECTS) {
            HttpURLConnection conn = null;
            try {
//                conn = (HttpURLConnection) new URL(info.realUrl).openConnection();
                conn = HttpsUtils.https(new URL(info.realUrl));
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(DEFAULT_TIMEOUT);
                conn.setReadTimeout(DEFAULT_TIMEOUT);

                addRequestHeaders(conn);

                final int code = conn.getResponseCode();
                if (DLCons.DEBUG) {
                    Log.d(TAG, "netCode:" + code);
                }
                switch (code) {
                    case HTTP_OK:
                    case HTTP_PARTIAL:
                        dlInit(conn, code);
                        return;
                    case HTTP_MOVED_PERM:
                    case HTTP_MOVED_TEMP:
                    case HTTP_SEE_OTHER:
                    case HTTP_NOT_MODIFIED:
                    case HTTP_TEMP_REDIRECT:
                        final String location = conn.getHeaderField("location");
                        if (TextUtils.isEmpty(location))
                            throw new DLException(
                                    "Can not obtain real url from location in header.");
                        info.realUrl = location;
                        info.redirect++;
                        continue;
                    default:
                        if (info.hasListener)
                            info.listener.onError(code, conn.getResponseMessage());
                        DLManager.getInstance(context).removeDLTask(info.baseUrl);
                        if (DLCons.DEBUG) {
                            Log.d(TAG, "error down." + info.baseUrl);
                        }
                        DLManager.getInstance(context).addDLTask();
                        return;
                }
            } catch (Exception e) {
                if (info.hasListener)
                    info.listener.onError(ERROR_OPEN_CONNECT, Log.getStackTraceString(e));
                DLManager.getInstance(context).removeDLTask(info.baseUrl);
                if (DLCons.DEBUG) {
                    Log.d(TAG, "error down." + info.baseUrl);
                }
                DLManager.getInstance(context).addDLTask();
                return;
            } finally {
                if (null != conn) conn.disconnect();
            }
        }
        throw new RuntimeException("Too many redirects");
    }

    private void dlInit(HttpURLConnection conn, int code) throws Exception {
        if (DLCons.DEBUG) {
            Log.d(TAG, "dlInit");
        }
        readResponseHeaders(conn);
        DLDBManager.getInstance(context).updateTaskInfo(info);
        if (!DLUtil.createFile(info.dirPath, info.fileName))
            throw new DLException("Can not create file");
        info.file = new File(info.dirPath, info.fileName);
        if (info.file.exists() && info.file.length() == info.totalBytes) {
            if (DLCons.DEBUG) {
                Log.d(TAG, "The file which we want to download was already here.");
            }
            //如果存在就回调出吧，别卡在这里吧
            onProgress(info.totalBytes);
            onFinish(null);
            return;
        }
        if (info.hasListener) info.listener.onStart(info.fileName, info.realUrl, info.totalBytes);
        switch (code) {
            case HTTP_OK:
                dlData(conn);
                break;
            case HTTP_PARTIAL:
                if (info.totalBytes <= 0) {
                    dlData(conn);
                } else {
                    if (DLCons.DEBUG) {
                        Log.d(TAG, "info.isResume." + info.isResume);
                    }
                    //info.isResume true,但是文件不存在了，获取不到文件大小的情况
                    if (info.isResume) {
                        //这里可以加逻辑判断如果文件不存在
                        for (DLThreadInfo threadInfo : info.threads) {
                            DLManager.getInstance(context)
                                    .addDLThread(new DLThread(context, threadInfo, info, this));
                        }
                    } else {
                        dlDispatch();
                    }
                }
                break;
        }
    }

    private void dlDispatch() {
        int threadSize = 1;
        //判断是否支持多线程下载文件
        boolean supportMultiThread = DLManager.getInstance(context).isSupportMultiThread();
        int threadLength = LENGTH_PER_THREAD;
        if (supportMultiThread) {//默认不允许多线程下载文件
            if (info.totalBytes <= LENGTH_PER_THREAD) {
                threadSize = 2;
                threadLength = info.totalBytes / threadSize;
            } else {
                threadSize = info.totalBytes / LENGTH_PER_THREAD;
            }
            int remainder = info.totalBytes % threadLength;
            for (int i = 0; i < threadSize; i++) {
                int start = i * threadLength;
                int end = start + threadLength - 1;
                if (i == threadSize - 1) {
                    end = start + threadLength + remainder - 1;
                }
                DLThreadInfo threadInfo =
                        new DLThreadInfo(UUID.randomUUID().toString(), info.baseUrl, start, start, end);
                info.addDLThread(threadInfo);
                DLDBManager.getInstance(context).insertThreadInfo(threadInfo);
                DLManager.getInstance(context).addDLThread(new DLThread(context, threadInfo, info, this));
            }
        } else {
            int start = 0;
            int end = info.totalBytes;
            DLThreadInfo threadInfo = new DLThreadInfo(UUID.randomUUID().toString(), info.baseUrl, start, start, end);
            info.addDLThread(threadInfo);
            DLDBManager.getInstance(context).insertThreadInfo(threadInfo);
            DLManager.getInstance(context).addDLThread(new DLThread(context, threadInfo, info, this));
        }
    }

    private void dlData(HttpURLConnection conn) throws IOException {
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(info.file);
        byte[] b = new byte[4096];
        int len;
        while (!info.isStop && (len = is.read(b)) != -1) {
            fos.write(b, 0, len);
            onProgress(len);
        }
        if (!info.isStop) {
            onFinish(null);
        } else {
            onStop(null);
        }
        fos.close();
        is.close();
    }

    private void addRequestHeaders(HttpURLConnection conn) {
        for (DLHeader header : info.requestHeaders) {
            conn.addRequestProperty(header.key, header.value);
        }
    }

    private void readResponseHeaders(HttpURLConnection conn) {
        info.disposition = conn.getHeaderField("Content-Disposition");
        info.location = conn.getHeaderField("Content-Location");
        info.mimeType = DLUtil.normalizeMimeType(conn.getContentType());
        final String transferEncoding = conn.getHeaderField("Transfer-Encoding");
        if (TextUtils.isEmpty(transferEncoding)) {
            try {
                info.totalBytes = Integer.parseInt(conn.getHeaderField("Content-Length"));
            } catch (NumberFormatException e) {
                info.totalBytes = -1;
            }
        } else {
            info.totalBytes = -1;
        }
        if (info.totalBytes == -1 && (TextUtils.isEmpty(transferEncoding) ||
                !transferEncoding.equalsIgnoreCase("chunked")))
            throw new RuntimeException("Can not obtain size of download file.");
        if (TextUtils.isEmpty(info.fileName))
            info.fileName = DLUtil.obtainFileName(info.realUrl, info.disposition, info.location);
    }
}