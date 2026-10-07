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
            if(count==-2){
              main.post(this::hideWindows);
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
    if(items==null)items=Collections.emptyList();

    int index=0;
    for(Item item:items){
      if(item.r.width()<24||item.r.height()<16)continue;
      Entry e;
      if(index<entries.size()){
        e=entries.get(index);
        updateEntry(e,item);
      }else{
        e=createEntry(item);
        if(e!=null)entries.add(e);
      }
      if(e!=null)e.v.setVisibility(View.VISIBLE);
      index++;
      if(index>=MAX_WINDOWS)break;
    }
    for(int i=index;i<entries.size();i++)entries.get(i).v.setVisibility(View.INVISIBLE);
  }

  private Entry createEntry(Item i){
    if(wm==null)return null;
    LabelView v=new LabelView(this);
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(
      32,24,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT);
    p.gravity=Gravity.TOP|Gravity.START;
    p.alpha=1f;
    p.setTitle("TaishanRU-item");
    Entry e=new Entry(v,p);
    updateEntry(e,i);
    try{
      wm.addView(v,p);
      return e;
    }catch(Throwable ignored){
      return null;
    }
  }

  private void updateEntry(Entry e,Item i){
    int ex=Math.min(24,Math.max(6,i.r.height()/4));
    int ey=Math.min(6,Math.max(2,i.r.height()/12));
    e.p.width=i.r.width()+ex*2;
    e.p.height=i.r.height()+ey*2;
    e.p.x=Math.max(0,i.r.left-ex);
    e.p.y=Math.max(0,i.r.top-ey);
    e.v.setContent(i.text,i.r.height());
    if(e.v.isAttachedToWindow()){
      try{wm.updateViewLayout(e.v,e.p);}catch(Throwable ignored){}
    }
  }

  private void hideWindows(){
    for(Entry e:entries)e.v.setVisibility(View.INVISIBLE);
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
    final LabelView v;
    final WindowManager.LayoutParams p;
    Entry(LabelView v,WindowManager.LayoutParams p){this.v=v;this.p=p;}
  }

  static class Item{
    final Rect r; final String text;
    Item(Rect r,String text){this.r=new Rect(r);this.text=text;}
  }

  static class LabelView extends View{
    final Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG), fg=new Paint(Paint.ANTI_ALIAS_FLAG);
    String text="";
    int sourceHeight=24;

    LabelView(Context c){
      super(c);
      setWillNotDraw(false);
      bg.setColor(Color.rgb(24,24,24));
      fg.setColor(Color.WHITE);
      fg.setTextAlign(Paint.Align.LEFT);
      fg.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
    }

    void setContent(String t,int h){
      text=t;
      sourceHeight=h;
      invalidate();
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
