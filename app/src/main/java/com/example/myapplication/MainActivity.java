package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // 요청할 권한 목록
    private final String[] PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            // Android 13 (API 33) 이상에서는 NEARBY_WIFI_DEVICES 권한이 필요합니다.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.NEARBY_WIFI_DEVICES : null
    };

    // 권한 요청 결과를 처리하는 ActivityResultLauncher
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // 모든 권한이 허용되었는지 확인
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }

                if (allGranted) {
                    startLoggerService(); // 모든 권한이 허용되면 서비스 시작
                } else {
                    Toast.makeText(this, "Required permissions are not granted.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(v -> checkPermissionsAndStartService());
    }

    private void checkPermissionsAndStartService() {
        // 이미 모든 권한이 있는지 확인
        boolean allPermissionsGranted = true;
        for (String perm : PERMISSIONS) {
            if (perm != null && ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }

        if (allPermissionsGranted) {
            startLoggerService(); // 권한이 이미 있으면 바로 서비스 시작
        } else {
            requestPermissionLauncher.launch(PERMISSIONS); // 권한이 없으면 요청
        }
    }

    private void startLoggerService() {
        Intent intent = new Intent(this, HandoverLoggerService.class);
        // Android 8 (Oreo) 이상에서는 포그라운드 서비스를 이렇게 시작해야 합니다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Handover Logger Service Started", Toast.LENGTH_SHORT).show();
    }
}