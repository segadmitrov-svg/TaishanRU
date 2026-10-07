package ru.taishan.localization;

import android.accessibilityservice.AccessibilityService;
import android.graphics.*;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.*;
import java.util.*;

public class TaishanAccessibilityService extends AccessibilityService {
  private final Handler handler=new Handler(Looper.getMainLooper());
  private WindowManager wm; private OverlayView overlay;
  private final Map<String,String> dict=new HashMap<>();

  @Override protected void onServiceConnected(){
    wm=(WindowManager)getSystemService(WINDOW_SERVICE); seed();
    overlay=new OverlayView();
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(
      -1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT);
    p.gravity=Gravity.TOP|Gravity.START; wm.addView(overlay,p);
  }
  @Override public void onAccessibilityEvent(AccessibilityEvent e){
    if(e==null||e.getPackageName()==null||!"com.huawei.hwcarcontrol".contentEquals(e.getPackageName()))return;
    handler.removeCallbacks(refresh); handler.postDelayed(refresh,60);
  }
  @Override public void onInterrupt(){}
  @Override public void onDestroy(){if(wm!=null&&overlay!=null)try{wm.removeView(overlay);}catch(Throwable ignored){} super.onDestroy();}
  private final Runnable refresh=()->{AccessibilityNodeInfo r=getRootInActiveWindow();List<Item>x=new ArrayList<>();if(r!=null)collect(r,x);if(overlay!=null)overlay.setItems(x);};
  private void collect(AccessibilityNodeInfo n,List<Item>o){
    if(n==null)return; CharSequence cs=n.getText();
    if(!TextUtils.isEmpty(cs)){String ru=dict.get(cs.toString().trim());if(ru!=null){Rect r=new Rect();n.getBoundsInScreen(r);if(r.width()>8&&r.height()>8)o.add(new Item(r,ru));}}
    for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){collect(c,o);c.recycle();}}
  }
  private void p(String a,String b){dict.put(a,b);}
  private void seed(){
    p("AC charging current","Ток зарядки AC");p("Battery Calibration","Калибровка батареи");
    p("Charging","Зарядка");p("Charging settings","Настройки зарядки");p("Vehicle settings","Настройки автомобиля");
    p("Driving","Вождение");p("Drive mode","Режим движения");p("Energy","Энергия");p("Battery","Батарея");
    p("Seats","Сиденья");p("Seat","Сиденье");p("Lights","Освещение");p("Lighting","Освещение");
    p("Doors","Двери");p("Windows","Окна");p("Mirrors","Зеркала");p("Suspension","Подвеска");
    p("Steering","Рулевое управление");p("ADAS","Системы помощи");p("Around view","Круговой обзор");
    p("Parking","Парковка");p("Safety","Безопасность");p("Comfort","Комфорт");p("Sound","Звук");
    p("Display","Экран");p("Climate","Климат");p("Air conditioning","Климат-контроль");
    p("Temperature","Температура");p("Fan speed","Скорость вентилятора");p("Auto","Авто");
    p("On","Вкл.");p("Off","Выкл.");p("Open","Открыть");p("Close","Закрыть");p("Enable","Включить");
    p("Disable","Отключить");p("High","Высокий");p("Medium","Средний");p("Low","Низкий");
    p("Normal","Обычный");p("Sport","Спорт");p("Eco","Эко");p("Snow","Снег");p("Custom","Пользовательский");
    p("Settings","Настройки");p("Reset","Сброс");p("Confirm","Подтвердить");p("Cancel","Отмена");
  }
  static class Item{final Rect r;final String t;Item(Rect r,String t){this.r=new Rect(r);this.t=t;}}
  class OverlayView extends View{
    final Paint bg=new Paint(1),fg=new Paint(1);List<Item>items=new ArrayList<>();
    OverlayView(){super(TaishanAccessibilityService.this);bg.setColor(Color.rgb(18,18,18));fg.setColor(Color.WHITE);fg.setTextAlign(Paint.Align.CENTER);}
    void setItems(List<Item>x){items=x;invalidate();}
    @Override protected void onDraw(Canvas c){for(Item i:items){Rect r=i.r;if(r.bottom<0||r.top>getHeight())continue;c.drawRoundRect(r.left,r.top,r.right,r.bottom,8,8,bg);float s=Math.max(12,r.height()*.48f);fg.setTextSize(s);while(fg.measureText(i.t)>r.width()*.92f&&s>10){fg.setTextSize(--s);}Paint.FontMetrics f=fg.getFontMetrics();c.drawText(i.t,r.centerX(),r.centerY()-(f.ascent+f.descent)/2,fg);}}
  }
}
