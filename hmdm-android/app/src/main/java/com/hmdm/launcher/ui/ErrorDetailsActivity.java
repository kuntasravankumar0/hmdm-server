package com.hmdm.launcher.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.databinding.ActivityErrorDetailsBinding;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

public class ErrorDetailsActivity extends AppCompatActivity {
    private ActivityErrorDetailsBinding binding;

    public static final String MESSAGE = "MESSAGE";
    public static void display(Activity parent, String message) {
        Intent intent = new Intent(parent, ErrorDetailsActivity.class);
        intent.putExtra(MESSAGE, message);
        parent.startActivity(intent);
    }

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_error_details);

        String message = getIntent().getStringExtra(MESSAGE);
        binding.editMessage.setText(message);
    }

    public void closeClicked(View view) {
        finish();
    }
}
