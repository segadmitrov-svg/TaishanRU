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
  private static final int PORT=28765;
  private static final String CHANNEL="taishan_ru_overlay";
  private static final int NOTIFY_ID=2203;
  private final Handler main=new Handler(Looper.getMainLooper());
  private volatile boolean running;
  private ServerSocket server;
  private Thread serverThread;
  private WindowManager wm;
  private OverlayView overlay;
  private boolean overlayAdded;

  @Override public void onCreate(){
    super.onCreate();
    startForegroundNow();
    running=true;
    startServer();
  }

  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(!running){running=true;startServer();}
    return START_STICKY;
  }

  @Override public IBinder onBind(Intent intent){return null;}

  @Override public void onDestroy(){
    running=false;
    try{if(server!=null)server.close();}catch(Throwable ignored){}
    if(serverThread!=null)serverThread.interrupt();
    removeOverlay();
    super.onDestroy();
  }

  private void startForegroundNow(){
    NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    if(Build.VERSION.SDK_INT>=26){
      NotificationChannel ch=new NotificationChannel(CHANNEL,"TaishanRU",NotificationManager.IMPORTANCE_MIN);
      ch.setDescription("Русская локализация меню автомобиля");
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    }
    Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
    b.setContentTitle("TaishanRU")
     .setContentText("Перевод меню активен")
     .setSmallIcon(android.R.drawable.ic_menu_info_details)
     .setOngoing(true)
     .setCategory(Notification.CATEGORY_SERVICE)
     .setShowWhen(false);
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
            if(count<0||count>500)continue;
            List<Item> items=new ArrayList<>(count);
            for(int i=0;i<count;i++){
              int l=in.readInt(),t=in.readInt(),r=in.readInt(),b=in.readInt();
              String text=in.readUTF();
              items.add(new Item(new Rect(l,t,r,b),text));
            }
            main.post(()->showItems(items));
          }catch(Throwable ignored){
            if(!running)break;
          }
        }
      }catch(Throwable ignored){}
    },"TaishanRU-overlay-server");
    serverThread.start();
  }

  private void showItems(List<Item> items){
    if(items==null||items.isEmpty()){
      if(overlayAdded&&overlay!=null)overlay.setItems(Collections.emptyList());
      return;
    }
    if(!Settings.canDrawOverlays(this))return;
    if(!ensureOverlay())return;
    overlay.setItems(items);
  }

  private boolean ensureOverlay(){
    if(overlayAdded)return true;
    try{
      wm=(WindowManager)getSystemService(WINDOW_SERVICE);
      overlay=new OverlayView(this);
      WindowManager.LayoutParams p=new WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
      p.gravity=Gravity.TOP|Gravity.START;
      p.alpha=1.0f;
      p.setTitle("TaishanRU");
      wm.addView(overlay,p);
      overlayAdded=true;
      return true;
    }catch(Throwable ignored){
      removeOverlay();
      return false;
    }
  }

  private void removeOverlay(){
    if(wm!=null&&overlay!=null&&overlayAdded){
      try{wm.removeView(overlay);}catch(Throwable ignored){}
    }
    overlayAdded=false;
    overlay=null;
    wm=null;
  }

  static class Item{
    final Rect r;
    final String text;
    Item(Rect r,String text){this.r=new Rect(r);this.text=text;}
  }

  static class OverlayView extends View{
    private final Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Item> items=new ArrayList<>();

    OverlayView(Context c){
      super(c);
      setWillNotDraw(false);
      bg.setColor(Color.argb(246,24,24,24));
      fg.setColor(Color.WHITE);
      fg.setTextAlign(Paint.Align.CENTER);
      fg.setFakeBoldText(true);
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
        float size=Math.max(20f,Math.min(44f,r.height()*.72f));
        fg.setTextSize(size);
        while(fg.measureText(i.text)>r.width()*.96f&&size>16f){
          size-=1f;
          fg.setTextSize(size);
        }
        float padX=Math.max(4f,size*.14f);
        float padY=Math.max(2f,size*.08f);
        float left=Math.max(0,r.left-padX);
        float right=Math.min(getWidth(),r.right+padX);
        float top=Math.max(0,r.top-padY);
        float bottom=Math.min(getHeight(),r.bottom+padY);
        c.drawRect(left,top,right,bottom,bg);
        Paint.FontMetrics fm=fg.getFontMetrics();
        float baseline=r.centerY()-(fm.ascent+fm.descent)/2f;
        c.drawText(i.text,r.left,baseline,fg);
      }
    }
  }
}
