package cn.replay30;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    public static final String EXTRA_SHORTCUT_CAPTURE = "cn.replay30.SHORTCUT_CAPTURE";
    private final Handler handler=new Handler();
    private TextView state,result;
    private TextView start,save,stop;
    private ReplayTimerView timer;
    private ScrollView contentScroll;
    private LinearLayout pageRoot;
    private Switch overlaySwitch;
    private boolean updatingOverlaySwitch;
    private boolean nightMode, updatingThemeSwitch;
    private boolean authorizing;
    private final Runnable refresh=new Runnable(){ public void run(){
        state.setText(ReplayService.active ? (ReplayService.saving ? "正在保存 · 缓存继续" : "● 正在缓存") : (ReplayService.starting ? "正在启动" : (ReplayService.paused ? "Ⅱ 已暂停 · 可保存" : "○ 未开始")));
        if (timer != null) timer.setStatus(ReplayService.active, ReplayService.saving, ReplayService.paused, ReplayService.available);
        result.setText(ReplayService.message);
        start.setEnabled(!ReplayService.active && !ReplayService.starting && !ReplayService.saving && !authorizing);
        save.setEnabled((ReplayService.active || ReplayService.paused) && ReplayService.available>0 && !ReplayService.saving);
        stop.setEnabled(ReplayService.active);
        start.setAlpha(start.isEnabled()?1f:.35f); save.setAlpha(save.isEnabled()?1f:.35f); stop.setAlpha(stop.isEnabled()?1f:.35f);
        handler.postDelayed(this,300);
    }};
    @Override public void onCreate(Bundle b){ super.onCreate(b); if(b!=null) authorizing=b.getBoolean("authorizing"); nightMode=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("night_mode",false);
        getWindow().setStatusBarColor(backgroundColor());
        getWindow().setNavigationBarColor(backgroundColor());
        getWindow().setBackgroundDrawable(new ColorDrawable(backgroundColor()));
        getWindow().setWindowAnimations(0);
        getWindow().getDecorView().setSystemUiVisibility(nightMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll=new ScrollView(this); contentScroll=scroll; scroll.setFillViewport(true); scroll.setBackgroundColor(backgroundColor());
        LinearLayout root=new LinearLayout(this); pageRoot=root; root.setOrientation(1); root.setPadding(dp(20),dp(12),dp(20),dp(28)); scroll.addView(root);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(20),dp(12)+i.getSystemWindowInsetTop(),dp(20),dp(28)+i.getSystemWindowInsetBottom()); return i;});
        LinearLayout titleRow=new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=addText(titleRow,"回录",34,primaryColor()); title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        titleRow.addView(new Space(this),new LinearLayout.LayoutParams(0,1,1)); addInfoButton(titleRow); root.addView(titleRow);
        TextView subtitle=addText(root,"屏幕回放 · 最近 30 秒",15,secondaryColor()); subtitle.setPadding(0,0,0,0);
        LinearLayout card=roundedCard(); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER); card.setPadding(dp(16),dp(20),dp(16),dp(18));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,dp(22),0,dp(14)); root.addView(card,cp);
        timer=new ReplayTimerView(this); card.addView(timer,new LinearLayout.LayoutParams(-1,dp(235)));
        timer.setNightMode(nightMode); state=addText(card,"○ 未开始",15,secondaryColor()); state.setGravity(Gravity.CENTER); state.setPadding(0,0,0,0);
        LinearLayout actions=roundedCard(); actions.setGravity(Gravity.CENTER); actions.setPadding(dp(4),dp(16),dp(4),dp(12)); root.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        start=actionButton(actions,"▶","开始缓存",Color.rgb(52,199,89),v->begin());
        save=actionButton(actions,"↓","保存回放",Color.rgb(0,122,255),v->command(ReplayService.SAVE));
        stop=actionButton(actions,"Ⅱ","暂停缓存",Color.rgb(255,59,48),v->command(ReplayService.STOP));
        result=addText(root,"",14,Color.rgb(88,86,214)); result.setPadding(dp(4),dp(14),dp(4),dp(6));
        TextView settingsTitle=addText(root,"快捷设置",13,secondaryColor()); settingsTitle.setPadding(dp(4),dp(22),0,dp(8));
        LinearLayout settingsCard=roundedCard(); settingsCard.setOrientation(LinearLayout.VERTICAL); settingsCard.setPadding(dp(16),0,dp(16),0); root.addView(settingsCard,new LinearLayout.LayoutParams(-1,-2));
        addOverlaySwitch(settingsCard);
        addThemeSwitch(settingsCard);
        settingRow(settingsCard,"系统快捷方式","添加到下拉控制中心",v->addQuickSettingsTile());
        TextView privacy=addText(root,"本地处理 · 不上传内容",13,secondaryColor()); privacy.setPadding(dp(4),dp(16),0,0);
        setContentView(scroll);
        overridePendingTransition(0,0);
        if(!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("disclaimer_accepted",false)) scroll.post(this::showDisclaimer);
        if (getIntent().getBooleanExtra(EXTRA_SHORTCUT_CAPTURE, false)) {
            getIntent().removeExtra(EXTRA_SHORTCUT_CAPTURE);
            root.post(this::begin);
        }
    }
    private TextView addText(LinearLayout root,String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);tagThemeText(t,color);t.setPadding(0,8,0,8);root.addView(t);return t;}
    private int backgroundColor(){return nightMode?Color.rgb(0,0,0):Color.rgb(242,242,247);}
    private int cardColor(){return nightMode?Color.rgb(28,28,30):Color.WHITE;}
    private int primaryColor(){return nightMode?Color.rgb(242,242,247):Color.rgb(28,28,30);}
    private int secondaryColor(){return nightMode?Color.rgb(174,174,178):Color.rgb(99,99,102);}
    private void tagThemeText(TextView text,int color){
        if(color==primaryColor()) text.setTag("theme_primary");
        else if(color==secondaryColor()) text.setTag("theme_secondary");
    }
    private void applyThemeInPlace(){
        getWindow().setStatusBarColor(backgroundColor()); getWindow().setNavigationBarColor(backgroundColor());
        getWindow().setBackgroundDrawable(new ColorDrawable(backgroundColor()));
        getWindow().getDecorView().setSystemUiVisibility(nightMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if(contentScroll!=null) contentScroll.setBackgroundColor(backgroundColor());
        if(timer!=null) timer.setNightMode(nightMode);
        if(pageRoot!=null) applyThemeToView(pageRoot);
    }
    private void applyThemeToView(View view){
        Object tag=view.getTag();
        if("theme_card".equals(tag)){
            GradientDrawable bg=new GradientDrawable(); bg.setColor(cardColor()); bg.setCornerRadius(dp(18)); view.setBackground(bg);
        } else if(view instanceof TextView){
            if("theme_primary".equals(tag)) ((TextView)view).setTextColor(primaryColor());
            else if("theme_secondary".equals(tag)) ((TextView)view).setTextColor(secondaryColor());
        }
        if(view instanceof ViewGroup){ ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++) applyThemeToView(group.getChildAt(i)); }
    }
    private LinearLayout roundedCard(){
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL);
        card.setTag("theme_card"); GradientDrawable bg=new GradientDrawable(); bg.setColor(cardColor()); bg.setCornerRadius(dp(18)); card.setBackground(bg); return card;
    }
    private void addInfoButton(LinearLayout root) {
        TextView info = new TextView(this);
        info.setText("ⓘ"); info.setTextSize(22); info.setTextColor(Color.WHITE); info.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(Color.rgb(0,122,255)); info.setBackground(bg);
        info.setContentDescription("查看使用说明"); info.setOnClickListener(v -> showGuideDialog());
        root.addView(info,new LinearLayout.LayoutParams(dp(36),dp(36)));
    }
    private void showGuideDialog() {
        final Dialog dialog = new Dialog(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24),dp(16),dp(24),dp(24));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(cardColor()); bg.setCornerRadius(dp(24)); page.setBackground(bg);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText("使用说明"); title.setTextSize(22); title.setTextColor(primaryColor()); title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView close = new TextView(this); close.setText("×"); close.setTextSize(30); close.setTextColor(primaryColor()); close.setGravity(Gravity.CENTER); close.setContentDescription("关闭说明"); close.setOnClickListener(v->dialog.dismiss());
        header.addView(close,new LinearLayout.LayoutParams(dp(40),dp(40))); page.addView(header);
        TextView text = new TextView(this); text.setTextSize(15); text.setTextColor(secondaryColor()); text.setLineSpacing(dp(5),1f);
        text.setText("先开启，再回放。只能保存开启缓存之后的画面。\n\n保存位置：Movies/Replay30。按开始缓存时的手机屏幕分辨率录制。\n\n应用会尝试录制允许捕获的应用播放声音，不录麦克风。受保护内容、通话和禁止捕获声音的应用可能无声或黑屏。\n\n息屏会停止并清理缓存。系统快捷方式可将“回录30秒”添加到下拉控制中心。\n\n点击暂停缓存会停止采集，但保留当前缓存；可继续保存。再次开始缓存或关闭应用时会清理该缓存。\n\n每次开始录屏都必须确认系统授权。高分辨率录制会增加耗电和发热。旋转后画面异常时，请停止后重新开始。");
        page.addView(text,new LinearLayout.LayoutParams(-1,-2));
        dialog.setContentView(page); dialog.setCanceledOnTouchOutside(false);
        Window window=dialog.getWindow(); if(window!=null){ window.setBackgroundDrawableResource(android.R.color.transparent); window.setLayout(-1,-2); }
        dialog.show(); if(dialog.getWindow()!=null) dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*.9f),-2);
    }
    private void showDisclaimer(){
        final Dialog dialog=new Dialog(this); dialog.setCanceledOnTouchOutside(false); dialog.setCancelable(false);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24),dp(22),dp(24),dp(20));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(cardColor()); bg.setCornerRadius(dp(24)); page.setBackground(bg);
        TextView title=new TextView(this); title.setText("免责声明"); title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(primaryColor()); page.addView(title);
        TextView text=new TextView(this); text.setTextSize(15); text.setLineSpacing(dp(5),1f); text.setTextColor(secondaryColor()); text.setPadding(0,dp(14),0,dp(20));
        text.setText("本应用只在您主动开始并确认安卓系统录屏授权后工作。\n\n请遵守当地法律、平台规则和他人隐私权。请勿录制、保存或传播未经许可的个人信息、受保护内容或违法内容。\n\n录屏结果、使用方式和分享行为由用户自行负责。受系统保护的画面或声音可能无法录制。\n\n本应用不上传录屏内容。未保存缓存可能在应用关闭或系统终止时丢失。"); page.addView(text);
        TextView accept=new TextView(this); accept.setText("我已知晓"); accept.setTextSize(17); accept.setTextColor(Color.WHITE); accept.setGravity(Gravity.CENTER);
        GradientDrawable buttonBg=new GradientDrawable(); buttonBg.setColor(Color.rgb(0,122,255)); buttonBg.setCornerRadius(dp(14)); accept.setBackground(buttonBg);
        accept.setOnClickListener(v->{getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("disclaimer_accepted",true).apply();dialog.dismiss();});
        page.addView(accept,new LinearLayout.LayoutParams(-1,dp(52)));
        dialog.setContentView(page); dialog.show(); if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*.9f),-2);}
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private void addQuickSettingsTile(){
        if(Build.VERSION.SDK_INT>=33){
            StatusBarManager manager=getSystemService(StatusBarManager.class);
            if(manager!=null) manager.requestAddTileService(new ComponentName(this,ReplayQuickTileService.class),"回录30秒",
                    Icon.createWithResource(this,android.R.drawable.presence_video_online),getMainExecutor(),result->{
                        if(result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) ReplayService.message="已添加到控制中心。";
                        else if(result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) ReplayService.message="控制中心已添加“回录30秒”。";
                        else showManualTileHelp();
                    });
            else showManualTileHelp();
            return;
        }
        Intent edit=new Intent("android.settings.QUICK_SETTINGS");
        if(edit.resolveActivity(getPackageManager())!=null) startActivity(edit);
        else showManualTileHelp();
    }
    private void showManualTileHelp(){
        new AlertDialog.Builder(this).setTitle("请手动添加快捷开关")
                .setMessage("此系统不能直接添加“回录30秒”。请下拉控制中心，点击编辑，再把“回录30秒”拖入快捷开关区域。")
                .setNegativeButton("知道了",null).setPositiveButton("打开设置",(d,w)->{
                    Intent edit=new Intent("android.settings.QUICK_SETTINGS");
                    if(edit.resolveActivity(getPackageManager())!=null) startActivity(edit); else startActivity(new Intent(Settings.ACTION_SETTINGS));
                }).show();
    }
    private void addOverlaySwitch(LinearLayout root) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(4),dp(12),dp(4),dp(12));
        TextView label=new TextView(this); label.setText("悬浮按钮"); label.setTextSize(17); label.setTextColor(primaryColor()); label.setTag("theme_primary");
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        overlaySwitch=new Switch(this); overlaySwitch.setShowText(false); overlaySwitch.setContentDescription("悬浮按钮开关");
        overlaySwitch.setOnCheckedChangeListener((button,checked)->{
            if(updatingOverlaySwitch) return;
            getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("overlay_enabled",checked).apply();
            if(checked && !Settings.canDrawOverlays(this)) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
            command(checked ? ReplayService.OVERLAY : ReplayService.HIDE_OVERLAY);
        });
        row.addView(overlaySwitch); root.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void addThemeSwitch(LinearLayout root){
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(4),dp(12),dp(4),dp(12));
        TextView label=new TextView(this); label.setText("深色模式"); label.setTextSize(17); label.setTextColor(primaryColor()); label.setTag("theme_primary"); row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this); toggle.setContentDescription("深色模式开关"); toggle.setChecked(nightMode);
        toggle.setOnCheckedChangeListener((button,checked)->{ if(updatingThemeSwitch)return;
            nightMode=checked; getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("night_mode",checked).apply(); applyThemeInPlace(); });
        row.addView(toggle); root.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView actionButton(LinearLayout row,String icon,String label,int color,View.OnClickListener click){
        LinearLayout item=new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        TextView button=new TextView(this); button.setText(icon); button.setTextSize(27); button.setTextColor(Color.WHITE); button.setGravity(Gravity.CENTER); button.setContentDescription(label); button.setOnClickListener(click);
        GradientDrawable bg=new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(color); button.setBackground(bg);
        item.addView(button,new LinearLayout.LayoutParams(dp(64),dp(64)));
        TextView caption=new TextView(this); caption.setText(label); caption.setTextSize(13); caption.setTextColor(primaryColor()); caption.setTag("theme_primary"); caption.setGravity(Gravity.CENTER); item.addView(caption,new LinearLayout.LayoutParams(-1,-2));
        row.addView(item,new LinearLayout.LayoutParams(0,-2,1)); return button;
    }
    private void settingRow(LinearLayout root,String title,String subtitle,View.OnClickListener click){
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(4),dp(12),dp(4),dp(12)); row.setOnClickListener(click);
        LinearLayout words=new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        TextView primary=new TextView(this); primary.setText(title); primary.setTextSize(17); primary.setTextColor(primaryColor()); primary.setTag("theme_primary"); words.addView(primary);
        TextView secondary=new TextView(this); secondary.setText(subtitle); secondary.setTextSize(13); secondary.setTextColor(secondaryColor()); secondary.setTag("theme_secondary"); words.addView(secondary);
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView chevron=new TextView(this); chevron.setText("›"); chevron.setTextSize(30); chevron.setTextColor(secondaryColor()); chevron.setTag("theme_secondary"); chevron.setGravity(Gravity.CENTER); row.addView(chevron,new LinearLayout.LayoutParams(dp(28),dp(40)));
        root.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private Button button(LinearLayout root,String text,View.OnClickListener click){Button b=new Button(this);b.setText(text);b.setTextSize(17);b.setAllCaps(false);b.setOnClickListener(click);root.addView(b,new LinearLayout.LayoutParams(-1,62*getResources().getDisplayMetrics().densityDpi/160));return b;}
    private void begin(){if(authorizing||ReplayService.active||ReplayService.starting)return; authorizing=true; start.setEnabled(false);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},12);
        else requestAudioThenCapture();
    }
    private void requestAudioThenCapture(){
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"},13);
        else requestCapture();
    }
    private void requestCapture(){startActivityForResult(((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent(),10);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);
        if(r==12) requestAudioThenCapture();
        else if(r==13 && g.length>0 && g[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) requestCapture();
        else if(r==13) { authorizing=false; ReplayService.message="未授予播放声音所需权限。尚未开始缓存。"; }
    }
    @Override protected void onActivityResult(int r,int code,Intent data){super.onActivityResult(r,code,data);if(r!=10)return;authorizing=false;
        if(code==RESULT_OK && data!=null){Intent i=new Intent(this,ReplayService.class).setAction(ReplayService.START).putExtra("code",code).putExtra("data",data);startForegroundService(i);}
        else ReplayService.message="已取消授权。尚未开始缓存。";
    }
    private void command(String action){startService(new Intent(this,ReplayService.class).setAction(action));}
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SHORTCUT_CAPTURE, false)) {
            intent.removeExtra(EXTRA_SHORTCUT_CAPTURE);
            begin();
        }
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("authorizing",authorizing);}
    @Override protected void onResume(){super.onResume();
        if(overlaySwitch!=null){ updatingOverlaySwitch=true; overlaySwitch.setChecked(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("overlay_enabled",false)); updatingOverlaySwitch=false; }
        handler.post(refresh); if(ReplayService.active) command(ReplayService.OVERLAY);
    }
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
