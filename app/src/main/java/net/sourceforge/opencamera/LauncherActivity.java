package net.sourceforge.opencamera;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public class LauncherActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        
        Button btnCamera = findViewById(R.id.btn_camera);
        Button btnViewer = findViewById(R.id.btn_viewer);
        
        btnCamera.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
        });
        
        btnViewer.setOnClickListener(v -> {
            Intent intent = new Intent(LauncherActivity.this, SimpleViewerActivity.class);
            startActivity(intent);
        });
    }
}
