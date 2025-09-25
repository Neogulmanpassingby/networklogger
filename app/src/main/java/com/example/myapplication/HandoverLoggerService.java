package com.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HandoverLoggerService (no-GPS version)
 */
public class HandoverLoggerService extends Service {
    private static final String TAG = "HandoverSvc";
    private static final String CH_ID = "handover_log_channel";
    private static final int NOTIF_ID = 1;
    private static final String CSV_NAME = "handover_dataset.csv";

    private static final long NORMAL_INTERVAL_MS = 2_000;
    private static final long FOCUS_INTERVAL_MS = 500;
    private static final long FOCUS_WINDOW_MS = 5_000;

    @IntDef({Transport.UNKNOWN, Transport.WIFI, Transport.CELLULAR})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Transport {
        int UNKNOWN = 0;
        int WIFI = 1;
        int CELLULAR = 2;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ConnectivityManager cm;
    private WifiManager wm;
    private TelephonyManager tm;

    private @Transport int currentTransport = Transport.UNKNOWN;
    private long focusUntilEpoch = 0L;

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void onCreate() {
        super.onCreate();

        createChannel();
        startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        registerNetworkCallback();
        ensureCsvHeader();
        scheduleNextSample(NORMAL_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ui.removeCallbacksAndMessages(null);

        if (cm != null && netCallback != null) {
            cm.unregisterNetworkCallback(netCallback);
        }

        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ─────────── Network callback ─────────── */
    private ConnectivityManager.NetworkCallback netCallback;

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private void registerNetworkCallback() {
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                updateTransport(network);
            }
        };

        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        cm.registerNetworkCallback(req, netCallback);
    }

    private void updateTransport(Network n) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);

        @Transport int newT = Transport.UNKNOWN;
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                newT = Transport.WIFI;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                newT = Transport.CELLULAR;
            }
        }

        if (newT == currentTransport || newT == Transport.UNKNOWN) return;

        collectSample("PRE");

        currentTransport = newT;
        focusUntilEpoch = System.currentTimeMillis() + FOCUS_WINDOW_MS;

        collectSample("POST");
    }

    /* ─────────── periodic sampler ─────────── */
    private void scheduleNextSample(long delayMs) {
        ui.postDelayed(() -> {
            collectSample("NORMAL");

            long next = System.currentTimeMillis() < focusUntilEpoch ? FOCUS_INTERVAL_MS : NORMAL_INTERVAL_MS;
            scheduleNextSample(next);
        }, delayMs);
    }

    /* ─────────── snapshot collector ─────────── */
    @SuppressLint("MissingPermission")
    private void collectSample(String event) {
        // 전체 데이터 수집 및 작성 프로세스를 백그라운드 스레드에서 실행합니다.
        io.execute(() -> {
            // ① 타임스탬프·Wi-Fi·Ping 은 먼저 구해 둡니다
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());

            int wifiRssi = 0;
            int wifiSpeed = 0;
            if (wm != null && hasWifiPerm()) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    wifiRssi = wi.getRssi();
                    wifiSpeed = wi.getLinkSpeed();
                }
            }

            // 시간이 오래 걸리는 ping 호출이 이제 백그라운드 스레드에서 안전하게 실행됩니다.
            PingStats ping = getPingStats();

            // 미리 계산
            int[] lte = {0, 0, 0, 0}; // rsrp, rsrq, sinr, rssi

            // ② Android 12+ 는 비동기 API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasLocationPerm() && hasPhoneStatePerm() && tm != null) {
                final int fWifiRssi = wifiRssi;
                final int fWifiSpeed = wifiSpeed;
                final PingStats fPing = ping;

                tm.requestCellInfoUpdate(
                        // 기존 'io' 실행기를 사용해도 됩니다.
                        io,
                        new TelephonyManager.CellInfoCallback() {
                            @Override
                            public void onCellInfo(@NonNull List<CellInfo> list) {
                                for (CellInfo ci : list) {
                                    if (ci instanceof CellInfoLte) {
                                        CellSignalStrengthLte ss = ((CellInfoLte) ci).getCellSignalStrength();
                                        lte[0] = ss.getRsrp();
                                        lte[1] = ss.getRsrq();
                                        lte[2] = ss.getRssnr();
                                        lte[3] = ss.getDbm();
                                        break;
                                    }
                                }
                                writeCsv(ts, event, fWifiRssi, fWifiSpeed, lte, fPing);
                            }
                        });

                return; // 👉 비동기 콜백에서 CSV 저장 끝
            }

            // ③ Android 11 이하: 기존 동기 API
            if (tm != null && hasLocationPerm() && hasPhoneStatePerm()) {
                List<CellInfo> infos = tm.getAllCellInfo();
                if (infos != null) {
                    for (CellInfo ci : infos) {
                        if (ci instanceof CellInfoLte) {
                            CellSignalStrengthLte ss = ((CellInfoLte) ci).getCellSignalStrength();
                            lte[0] = ss.getRsrp();
                            lte[1] = ss.getRsrq();
                            lte[2] = ss.getRssnr();
                            lte[3] = ss.getDbm();
                            break;
                        }
                    }
                }
            }

            // ④ 동기 경로는 여기서 바로 CSV 기록
            writeCsv(ts, event, wifiRssi, wifiSpeed, lte, ping);
        });
    }

    /* ---------- CSV 행 작성 전용 헬퍼 ---------- */
    private void writeCsv(String ts, String event, int wifiRssi, int wifiSpeed, int[] lte, PingStats ping) {
        String row = TextUtils.join(",", new Object[]{
                ts,
                event,
                tName(currentTransport),
                wifiRssi,
                wifiSpeed,
                lte[0],
                lte[1],
                lte[2],
                lte[3],
                ping.avg,
                ping.jitter,
                ping.loss
        });

        appendCsv(row);
    }

    /* ─────────── helpers ─────────── */
    private boolean has(@NonNull String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPerm() {
        return has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean hasWifiPerm() {
        return Build.VERSION.SDK_INT < 33 || has(Manifest.permission.NEARBY_WIFI_DEVICES);
    }

    private boolean hasPhoneStatePerm() {
        return has(Manifest.permission.READ_PHONE_STATE);
    }

    private int getPingAvgMs() {
        try {
            Process p = Runtime.getRuntime().exec("ping -c 3 -q 8.8.8.8");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("avg")) {
                    String[] parts = line.split("=");
                    if (parts.length > 1) {
                        String[] vals = parts[1].trim().split("/");
                        return (int) Float.parseFloat(vals[1]);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ping", e);
        }
        return -1;
    }

    private static class PingStats {
        int avg = -1;   // ms
        int jitter = -1; // ms (mdev)
        float loss = -1; // %
    }

    private PingStats getPingStats() {
        PingStats ps = new PingStats();
        try {
            Process p = Runtime.getRuntime().exec("ping -c 50 -q 8.8.8.8");
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("packets transmitted")) {
                    // "... 10 packets transmitted, 2 receivaed, 80% packet loss ..."
                    ps.loss = Float.parseFloat(line.split(",")[2].trim().split("%")[0]);
                }
                if (line.startsWith("rtt ") || line.startsWith("round-trip")) {
                    String[] vals = line.split("=")[1].trim().split("/");
                    ps.avg    = (int) Float.parseFloat(vals[1]);                   // 89.827
                    ps.jitter = (int) Float.parseFloat(vals[3].replace(" ms","")); // 3.663
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ping", e);
        }
        return ps;
    }

    /* ─────────── CSV utilities ─────────── */
    private void ensureCsvHeader() {
        io.execute(() -> {
            File f = new File(getExternalFilesDir(null), CSV_NAME);
            if (!f.exists()) {
                appendCsv("ts,event,transport,wifi_rssi,wifi_speed,"
                        + "lte_rsrp,lte_rsrq,lte_sinr,lte_rssi,"
                        + "ping_avg_ms,jitter_ms,loss_pct");
            }
        });
    }

    private void appendCsv(String line) {
        try {
            File file = new File(getExternalFilesDir(null), CSV_NAME);
            try (FileWriter w = new FileWriter(file, true)) {
                w.append(line).append('\n');
            }
        } catch (IOException e) {
            Log.e(TAG, "csv", e);
        }
    }

    /* ─────────── notification helpers ─────────── */
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID,
                    "Handover Logger",
                    NotificationManager.IMPORTANCE_LOW
            );
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Handover Logger")
                .setContentText("Collecting training data…")
                .setOngoing(true)
                .build();
    }

    /* ─────────── util ─────────── */
    private static String tName(@Transport int t) {
        switch (t) {
            case Transport.WIFI:
                return "wifi";
            case Transport.CELLULAR:
                return "cellular";
            default:
                return "unknown";
        }
    }
}
