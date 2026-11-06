package com.fandazi.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(IconAliasPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
