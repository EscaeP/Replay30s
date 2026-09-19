package cn.replay30;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Visual timer for the available replay window. */
public final class ReplayTimerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float seconds;
    private boolean saving, active, paused;
    private boolean nightMode;
    public ReplayTimerView(Context context) { super(context); setContentDescription("当前可保存时长"); }
    public void setNightMode(boolean enabled) { if (nightMode != enabled) { nightMode = enabled; invalidate(); } }
    public void setStatus(boolean nowActive, boolean nowSaving, boolean nowPaused, double available) {
        float next = (float) available;
        if (active != nowActive || saving != nowSaving || paused != nowPaused || Math.abs(seconds-next) > .1f) {
            active=nowActive; saving=nowSaving; paused=nowPaused; seconds=next; invalidate();
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        float d=getResources().getDisplayMetrics().density, size=Math.min(getWidth(),getHeight());
        float cx=getWidth()/2f, cy=getHeight()/2f, r=size*.34f;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(12*d); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(nightMode ? Color.rgb(72,72,74) : Color.rgb(229,229,234)); canvas.drawCircle(cx,cy,r,paint);
        paint.setColor(saving ? Color.rgb(255,149,0) : Color.rgb(0,122,255));
        canvas.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-90,Math.min(1f,seconds/30f)*360,false,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(nightMode ? Color.rgb(242,242,247) : Color.rgb(28,28,30)); paint.setTextSize(43*d);
        canvas.drawText(String.format(java.util.Locale.CHINA,"%04.1f",seconds),cx,cy+7*d,paint);
        paint.setColor(nightMode ? Color.rgb(174,174,178) : Color.DKGRAY); paint.setTextSize(14*d);
        canvas.drawText(saving ? "正在保存，缓存继续" : (active ? "当前可保存秒数" : (paused ? "已暂停，可继续保存" : "最多保存 30 秒")),cx,cy+33*d,paint);
    }
    @Override protected void onMeasure(int w,int h){int height=(int)(235*getResources().getDisplayMetrics().density+.5f);setMeasuredDimension(MeasureSpec.getSize(w),height);}
}
