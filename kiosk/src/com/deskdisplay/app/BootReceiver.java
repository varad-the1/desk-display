package com.deskdisplay.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent l = new Intent(c, MainActivity.class);
        l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(l);
    }
}
