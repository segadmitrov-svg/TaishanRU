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
import java.util.concurrent.*;

public class TaishanAccessibilityService extends AccessibilityService {
  private static final String CAR_PKG="com.huawei.hwcarcontrol";
  private static final int PORT=28765;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Map<String,String> dict=new HashMap<>();
  private String lastSentSignature="";
  private volatile boolean sendInFlight=false;
  private final ExecutorService io=Executors.newSingleThreadExecutor();

  private final Runnable poll=new Runnable(){
    @Override public void run(){
      refreshNow();
      handler.postDelayed(this,500);
    }
  };

  @Override protected void onServiceConnected(){
    super.onServiceConnected();
    seed();
    handler.removeCallbacks(poll);
    handler.post(poll);
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent e){
    if(e==null || e.getPackageName()==null || !CAR_PKG.contentEquals(e.getPackageName()))return;
    handler.removeCallbacks(refreshOnce);
    if(e.getEventType()==AccessibilityEvent.TYPE_VIEW_SCROLLED){
      int dy=0;
      try{dy=e.getScrollDeltaY();}catch(Throwable ignored){}
      if(dy!=0)sendScrollDeltaAsync(dy);
      handler.postDelayed(refreshOnce,35);
    }else{
      handler.postDelayed(refreshOnce,70);
    }
  }

  @Override public void onInterrupt(){}

  @Override public void onDestroy(){
    handler.removeCallbacksAndMessages(null);
    try{io.shutdownNow();}catch(Throwable ignored){}
    super.onDestroy();
  }

  private final Runnable refreshOnce=this::refreshNow;

  private void sendScrollDeltaAsync(int dy){
    io.execute(()->sendScrollDelta(dy));
  }

  private boolean sendScrollDelta(int dy){
    try(Socket s=new Socket()){
      s.connect(new InetSocketAddress("127.0.0.1",PORT),120);
      DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
      out.writeInt(-1);
      out.writeInt(dy);
      out.flush();
      return true;
    }catch(Throwable ignored){
      return false;
    }
  }

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
      if(sig.equals(lastSentSignature) || sendInFlight)return;
      sendInFlight=true;
      io.execute(()->{
        try{
          if(sendItems(items))lastSentSignature=sig;
        }finally{
          sendInFlight=false;
        }
      });
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
        if(r.width()>24 && r.height()>16 && src.length()<=64 && ru.length()<=72 && out.size()<28){
          out.add(new Item(r,ru));
        }
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
    p("Driver assistance","Помощь водителю");
    p("Connections","Подключения");
    p("Assistant","Ассистент");
    p("Vehicle status","Состояние автомобиля");
    p("System","Система");
    p("Head-up display","Проекционный дисплей");
    p("HUD","Проекционный дисплей");
    p("Brightness","Яркость");
    p("Theme","Тема");
    p("Day mode","Дневной режим");
    p("Night mode","Ночной режим");
    p("Automatic","Автоматически");
    p("Language","Язык");
    p("Units","Единицы измерения");
    p("Time","Время");
    p("Date","Дата");
    p("Bluetooth","Bluetooth");
    p("Wi-Fi","Wi-Fi");
    p("Hotspot","Точка доступа");
    p("Mobile network","Мобильная сеть");
    p("Navigation","Навигация");
    p("Audio","Аудио");
    p("Volume","Громкость");
    p("Balance","Баланс");
    p("Equalizer","Эквалайзер");
    p("Ambient light","Атмосферная подсветка");
    p("Lock","Блокировка");
    p("Unlock","Разблокировать");
    p("Child lock","Детский замок");
    p("Auto lock","Автоблокировка");
    p("Trunk","Багажник");
    p("Tailgate","Дверь багажника");
    p("Sunroof","Люк");
    p("Wipers","Стеклоочистители");
    p("Headlights","Фары");
    p("Auto high beam","Автоматический дальний свет");
    p("Lane keeping assist","Удержание в полосе");
    p("Adaptive cruise control","Адаптивный круиз-контроль");
    p("Collision warning","Предупреждение о столкновении");
    p("Emergency braking","Экстренное торможение");
    p("Blind spot monitoring","Контроль слепых зон");
    p("Traffic sign recognition","Распознавание дорожных знаков");
    p("Parking assist","Помощь при парковке");
    p("Camera","Камера");
    p("Tire pressure","Давление в шинах");
    p("Service","Сервис");
    p("Maintenance","Обслуживание");
    p("Software update","Обновление ПО");
    p("About","О системе");
    p("Privacy","Конфиденциальность");
    p("Restore factory settings","Сброс к заводским настройкам");

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
