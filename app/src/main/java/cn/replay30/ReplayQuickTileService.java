package cn.replay30;

import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A Control Center / Quick Settings tile for Android. */
public final class ReplayQuickTileService extends TileService {
    @Override public void onStartListening() { super.onStartListening(); refreshTile(); }
    @Override public void onClick() {
        super.onClick();
        if (ReplayService.active || ReplayService.paused) {
            startService(new Intent(this, ReplayService.class).setAction(ReplayService.SAVE));
        } else {
            Intent open=new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_SHORTCUT_CAPTURE,true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(Build.VERSION.SDK_INT>=34){
                PendingIntent pending=PendingIntent.getActivity(this,30,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pending);
            } else startActivityAndCollapse(open);
        }
        refreshTile();
    }
    private void refreshTile(){
        Tile tile=getQsTile(); if(tile==null)return;
        tile.setLabel("回录30秒");
        tile.setState(ReplayService.active?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
