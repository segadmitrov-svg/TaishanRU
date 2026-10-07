package ru.taishan.localization;

import android.accessibilityservice.AccessibilityService;
import android.graphics.*;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.*;
import java.util.*;
import java.util.regex.*;

public class TaishanAccessibilityService extends AccessibilityService {
  private static final String CAR_PKG="com.huawei.hwcarcontrol";
  private final Handler handler=new Handler(Looper.getMainLooper());
  private WindowManager wm;
  private Context windowContext;
  private OverlayView overlay;
  private boolean overlayAdded=false;
  private int carDisplayId=Display.DEFAULT_DISPLAY;
  private int overlayDisplayId=-1;
  private final Map<String,String> dict=new HashMap<>();
  private String lastSignature="";

  private final Runnable poll=new Runnable(){
    @Override public void run(){
      refreshNow();
      handler.postDelayed(this,450);
    }
  };

  @Override protected void onServiceConnected(){
    super.onServiceConnected();
    seed();
    handler.removeCallbacks(poll);
    handler.post(poll);
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent e){
    if(e!=null && e.getPackageName()!=null && CAR_PKG.contentEquals(e.getPackageName())){
      handler.removeCallbacks(refreshOnce);
      handler.postDelayed(refreshOnce,40);
    }
  }

  @Override public void onInterrupt(){}

  @Override public void onDestroy(){
    handler.removeCallbacksAndMessages(null);
    removeOverlay();
    super.onDestroy();
  }

  private final Runnable refreshOnce=this::refreshNow;

  private AccessibilityNodeInfo findCarRoot(){
    try{
      List<AccessibilityWindowInfo> windows=getWindows();
      if(windows!=null){
        for(AccessibilityWindowInfo w:windows){
          if(w==null)continue;
          AccessibilityNodeInfo r=null;
          try{
            r=w.getRoot();
            if(r!=null && r.getPackageName()!=null && CAR_PKG.contentEquals(r.getPackageName())){
              try{carDisplayId=w.getDisplayId();}catch(Throwable ignored){}
              return r;
            }
          }catch(Throwable ignored){
          }finally{
            if(r!=null && (r.getPackageName()==null || !CAR_PKG.contentEquals(r.getPackageName()))) r.recycle();
          }
        }
      }
    }catch(Throwable ignored){}
    try{
      AccessibilityNodeInfo r=getRootInActiveWindow();
      if(r!=null && r.getPackageName()!=null && CAR_PKG.contentEquals(r.getPackageName())) return r;
      if(r!=null)r.recycle();
    }catch(Throwable ignored){}
    return null;
  }

  private void refreshNow(){
    AccessibilityNodeInfo root=null;
    try{
      root=findCarRoot();
      if(root==null){
        if(overlayAdded && overlay!=null)overlay.setItems(Collections.emptyList());
        lastSignature="";
        return;
      }
      List<Item> items=new ArrayList<>();
      collect(root,items);
      String sig=signature(items);
      if(!sig.equals(lastSignature)){
        lastSignature=sig;
        if(items.isEmpty()){
          if(overlayAdded && overlay!=null)overlay.setItems(items);
        }else if(ensureOverlay()){
          overlay.setItems(items);
        }
      }
    }catch(Throwable ignored){
    }finally{
      if(root!=null)try{root.recycle();}catch(Throwable ignored){}
    }
  }

  private String signature(List<Item> items){
    StringBuilder b=new StringBuilder();
    for(Item i:items)b.append(i.r.flattenToString()).append('|').append(i.t).append(';');
    return b.toString();
  }

  private boolean ensureOverlay(){
    if(overlayAdded && overlayDisplayId==carDisplayId)return true;
    if(overlayAdded)removeOverlay();
    try{
      DisplayManager dm=(DisplayManager)getSystemService(DISPLAY_SERVICE);
      Display d=dm!=null?dm.getDisplay(carDisplayId):null;
      Context displayContext=d!=null?createDisplayContext(d):this;
      if(Build.VERSION.SDK_INT>=30){
        windowContext=displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
      }else{
        windowContext=displayContext;
      }
      wm=(WindowManager)windowContext.getSystemService(WINDOW_SERVICE);
      overlay=new OverlayView(windowContext);
      WindowManager.LayoutParams p=new WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
      p.gravity=Gravity.TOP|Gravity.START;
      wm.addView(overlay,p);
      overlayAdded=true;
      overlayDisplayId=carDisplayId;
      return true;
    }catch(Throwable ignored){
      removeOverlay();
      return false;
    }
  }

  private void removeOverlay(){
    if(wm!=null && overlay!=null && overlayAdded){
      try{wm.removeView(overlay);}catch(Throwable ignored){}
    }
    overlayAdded=false;
    overlayDisplayId=-1;
    overlay=null;
    wm=null;
    windowContext=null;
  }

  private void collect(AccessibilityNodeInfo n,List<Item> out){
    if(n==null)return;
    CharSequence cs=n.getText();
    if(!TextUtils.isEmpty(cs)){
      String src=cs.toString().trim();
      String ru=translate(src,n.getViewIdResourceName());
      if(ru!=null && !ru.equals(src)){
        Rect r=new Rect();
        n.getBoundsInScreen(r);
        if(r.width()>8 && r.height()>8)out.add(new Item(r,ru));
      }
    }
    for(int i=0;i<n.getChildCount();i++){
      AccessibilityNodeInfo c=n.getChild(i);
      if(c!=null){
        collect(c,out);
        try{c.recycle();}catch(Throwable ignored){}
      }
    }
  }

  private String translate(String s,String id){
    String exact=dict.get(s);
    if(exact!=null)return exact;

    Matcher m=Pattern.compile("^Battery:\\s*(.+)$",Pattern.CASE_INSENSITIVE).matcher(s);
    if(m.matches())return "Батарея: "+m.group(1);
    m=Pattern.compile("^Fuel:\\s*(.+)$",Pattern.CASE_INSENSITIVE).matcher(s);
    if(m.matches())return "Топливо: "+m.group(1);
    m=Pattern.compile("^Daily,\\s*(.+)$",Pattern.CASE_INSENSITIVE).matcher(s);
    if(m.matches())return "Ежедневно, "+m.group(1);

    if(id!=null){
      if(id.endsWith("/total_remain_mileage_title"))return "Запас хода";
      if(id.endsWith("/remain_electricity_title") && s.startsWith("Battery:"))return "Батарея: "+s.substring(8).trim();
      if(id.endsWith("/remain_fuel_title") && s.startsWith("Fuel:"))return "Топливо: "+s.substring(5).trim();
    }
    return null;
  }

  private void p(String a,String b){dict.put(a,b);}

  private void seed(){
    p("AC charging current","Ток зарядки AC");
    p("Battery Calibration","Калибровка батареи");
    p("Charging","Зарядка");
    p("Charging settings","Настройки зарядки");
    p("Vehicle settings","Настройки автомобиля");
    p("Driving","Вождение");
    p("Drive mode","Режим движения");
    p("Energy","Энергия");
    p("Battery","Батарея");
    p("Seats","Сиденья");
    p("Seat","Сиденье");
    p("Lights","Освещение");
    p("Lighting","Освещение");
    p("Doors","Двери");
    p("Windows","Окна");
    p("Mirrors","Зеркала");
    p("Suspension","Подвеска");
    p("Steering","Рулевое управление");
    p("ADAS","Системы помощи");
    p("Around view","Круговой обзор");
    p("Parking","Парковка");
    p("Safety","Безопасность");
    p("Comfort","Комфорт");
    p("Sound","Звук");
    p("Display","Экран");
    p("Climate","Климат");
    p("Air conditioning","Климат-контроль");
    p("Temperature","Температура");
    p("Fan speed","Скорость вентилятора");
    p("Auto","Авто");
    p("On","Вкл.");
    p("Off","Выкл.");
    p("Open","Открыть");
    p("Close","Закрыть");
    p("Enable","Включить");
    p("Disable","Отключить");
    p("High","Высокий");
    p("Medium","Средний");
    p("Low","Низкий");
    p("Normal","Обычный");
    p("Sport","Спорт");
    p("Eco","Эко");
    p("Snow","Снег");
    p("Custom","Пользовательский");
    p("Settings","Настройки");
    p("Reset","Сброс");
    p("Confirm","Подтвердить");
    p("Cancel","Отмена");

    p("Fuel detection mode","Режим проверки выбросов");
    p("Detect vehicle exhaust emission index during annual inspection.","Проверка показателей выхлопа при техосмотре.");
    p("Driving overview","Пробег автомобиля");
    p("Maintenance mode","Сервисный режим");
    p("Vehicle repair and inspection setup","Настройки ремонта и техосмотра");

    p("Range","Запас хода");
    p("Scheduled charging","Зарядка по расписанию");
    p("Charging protect","Защита зарядки");
    p("You can set the charging limit and current limit.","Можно задать предел заряда и ограничение тока.");
    p("Vehicle-to-vehicle discharge","Разрядка на другой автомобиль");
    p("Turn on the switch to discharge to other cars","Включите для передачи энергии другому автомобилю");
    p("220V socket in vehicle","Розетка 220 В в автомобиле");
    p("When turned on, the three-hole socket can be energized","При включении питание подаётся на розетку 220 В");
    p("External discharge cut-off","Предел внешней разрядки");
    p("Charging light effect","Световая индикация зарядки");
  }

  static class Item{
    final Rect r;
    final String t;
    Item(Rect r,String t){this.r=new Rect(r);this.t=t;}
  }

  class OverlayView extends View{
    final Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint fg=new Paint(Paint.ANTI_ALIAS_FLAG);
    List<Item> items=new ArrayList<>();

    OverlayView(Context context){
      super(context);
      setWillNotDraw(false);
      bg.setColor(Color.rgb(24,24,24));
      fg.setColor(Color.WHITE);
      fg.setTextAlign(Paint.Align.CENTER);
    }

    void setItems(List<Item> x){
      items=new ArrayList<>(x);
      invalidate();
    }

    @Override protected void onDraw(Canvas c){
      super.onDraw(c);
      for(Item i:items){
        Rect r=i.r;
        if(r.right<=0||r.bottom<=0||r.left>=getWidth()||r.top>=getHeight())continue;
        c.drawRoundRect(r.left,r.top,r.right,r.bottom,8,8,bg);
        float s=Math.max(12f,r.height()*.48f);
        fg.setTextSize(s);
        while(fg.measureText(i.t)>r.width()*.92f && s>10f){fg.setTextSize(--s);}
        Paint.FontMetrics f=fg.getFontMetrics();
        c.drawText(i.t,r.centerX(),r.centerY()-(f.ascent+f.descent)/2f,fg);
      }
    }
  }
}
