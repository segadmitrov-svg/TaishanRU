package ru.taishan.localization;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.*;
import android.text.TextUtils;
import android.view.accessibility.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class TaishanAccessibilityService extends AccessibilityService {
  private static final String CAR_PKG="com.huawei.hwcarcontrol";
  private static final int PORT=28765;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Map<String,String> dict=new HashMap<>();
  private String lastSentSignature="";

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
            if(r!=null && r.getPackageName()!=null && CAR_PKG.contentEquals(r.getPackageName())) return r;
          }catch(Throwable ignored){
          }finally{
            if(r!=null && (r.getPackageName()==null || !CAR_PKG.contentEquals(r.getPackageName()))){
              try{r.recycle();}catch(Throwable ignored){}
            }
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
      List<Item> items=new ArrayList<>();
      if(root!=null)collect(root,items);
      final String sig=signature(items);
      if(sig.equals(lastSentSignature))return;
      new Thread(()->{
        if(sendItems(items))lastSentSignature=sig;
      },"TaishanRU-send").start();
    }catch(Throwable ignored){
    }finally{
      if(root!=null)try{root.recycle();}catch(Throwable ignored){}
    }
  }

  private boolean sendItems(List<Item> items){
    try(Socket s=new Socket()){
      s.connect(new InetSocketAddress("127.0.0.1",PORT),180);
      s.setSoTimeout(300);
      DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
      out.writeInt(items.size());
      for(Item i:items){
        out.writeInt(i.r.left);
        out.writeInt(i.r.top);
        out.writeInt(i.r.right);
        out.writeInt(i.r.bottom);
        out.writeUTF(i.t);
      }
      out.flush();
      return true;
    }catch(Throwable ignored){
      return false;
    }
  }

  private String signature(List<Item> items){
    StringBuilder b=new StringBuilder();
    for(Item i:items)b.append(i.r.flattenToString()).append('|').append(i.t).append(';');
    return b.toString();
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
}
