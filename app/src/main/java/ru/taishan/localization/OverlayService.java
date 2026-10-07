package ru.taishan.localization;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class OverlayService extends Service {
  private static final int PORT=28765, MAX_WINDOWS=24;
  private static final String CHANNEL="taishan_ru_overlay";
  private static final int NOTIFY_ID=2203;

  private final Handler main=new Handler(Looper.getMainLooper());
  private final List<Entry> entries=new ArrayList<>();
  private volatile boolean running;
  private ServerSocket server;
  private Thread serverThread;
  private WindowManager wm;

  @Override public void onCreate(){
    super.onCreate();
    startForegroundNow();
    wm=(WindowManager)getSystemService(WINDOW_SERVICE);
    running=true;
    startServer();
  }

  @Override public int onStartCommand(Intent i,int f,int id){
    if(!running){running=true;startServer();}
    return START_STICKY;
  }

  @Override public IBinder onBind(Intent i){return null;}

  @Override public void onDestroy(){
    running=false;
    try{if(server!=null)server.close();}catch(Throwable ignored){}
    clearWindows();
    super.onDestroy();
  }

  private void startForegroundNow(){
    NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    if(Build.VERSION.SDK_INT>=26){
      NotificationChannel ch=new NotificationChannel(CHANNEL,"TaishanRU",NotificationManager.IMPORTANCE_MIN);
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    }
    Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
    b.setContentTitle("TaishanRU").setContentText("Перевод меню активен")
      .setSmallIcon(android.R.drawable.ic_menu_info_details).setOngoing(true).setShowWhen(false);
    startForeground(NOTIFY_ID,b.build());
  }

  private void startServer(){
    serverThread=new Thread(()->{
      try{
        server=new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT));
        while(running){
          try(Socket s=server.accept()){
            DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
            int count=in.readInt();
            if(count==-1){
              int dy=in.readInt();
              main.post(()->shiftWindows(dy));
              continue;
            }
            if(count<0||count>500)continue;
            List<Item> items=new ArrayList<>();
            for(int n=0;n<count;n++){
              int l=in.readInt(),t=in.readInt(),r=in.readInt(),b=in.readInt();
              String text=in.readUTF();
              if(items.size()<MAX_WINDOWS)items.add(new Item(new Rect(l,t,r,b),text));
            }
            main.post(()->showItems(items));
          }catch(Throwable ignored){if(!running)break;}
        }
      }catch(Throwable ignored){}
    },"TaishanRU-overlay-server");
    serverThread.start();
  }

  private void showItems(List<Item> items){
    if(!Settings.canDrawOverlays(this))return;
    clearWindows();
    if(items==null)return;
    for(Item i:items)addWindow(i);
  }

  private void addWindow(Item i){
    if(wm==null||i.r.width()<24||i.r.height()<16)return;
    int ex=Math.min(24,Math.max(6,i.r.height()/4));
    int ey=Math.min(6,Math.max(2,i.r.height()/12));
    int w=i.r.width()+ex*2, h=i.r.height()+ey*2;
    int x=Math.max(0,i.r.left-ex), y=Math.max(0,i.r.top-ey);

    LabelView v=new LabelView(this,i.text,i.r.height());
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(
      w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT);
    p.gravity=Gravity.TOP|Gravity.START;
    p.x=x;p.y=y;p.alpha=1f;p.setTitle("TaishanRU-item");
    try{wm.addView(v,p);entries.add(new Entry(v,p));}catch(Throwable ignored){}
  }

  private void shiftWindows(int dy){
    if(wm==null||dy==0)return;
    for(Entry e:new ArrayList<>(entries)){
      try{
        e.p.y-=dy;
        wm.updateViewLayout(e.v,e.p);
      }catch(Throwable ignored){}
    }
  }

  private void clearWindows(){
    if(wm!=null){
      for(Entry e:new ArrayList<>(entries)){
        try{wm.removeViewImmediate(e.v);}catch(Throwable ignored){}
      }
    }
    entries.clear();
  }

  static class Entry{
    final View v;
    final WindowManager.LayoutParams p;
    Entry(View v,WindowManager.LayoutParams p){this.v=v;this.p=p;}
  }

  static class Item{
    final Rect r; final String text;
    Item(Rect r,String text){this.r=new Rect(r);this.text=text;}
  }

  static class LabelView extends View{
    final Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG), fg=new Paint(Paint.ANTI_ALIAS_FLAG);
    final String text; final int sourceHeight;
    LabelView(Context c,String t,int h){
      super(c);text=t;sourceHeight=h;setWillNotDraw(false);
      bg.setColor(Color.rgb(24,24,24));fg.setColor(Color.WHITE);
      fg.setTextAlign(Paint.Align.LEFT);
      fg.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
    }
    @Override protected void onDraw(Canvas c){
      super.onDraw(c);
      c.drawRect(0,0,getWidth(),getHeight(),bg);
      float s=Math.max(18f,Math.min(36f,sourceHeight*.54f));
      fg.setTextSize(s);
      while(fg.measureText(text)>getWidth()-16f&&s>14f){s-=1f;fg.setTextSize(s);}
      Paint.FontMetrics fm=fg.getFontMetrics();
      float base=getHeight()/2f-(fm.ascent+fm.descent)/2f;
      c.drawText(text,8f,base,fg);
    }
  }
}
