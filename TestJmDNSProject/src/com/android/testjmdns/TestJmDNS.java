package com.android.testjmdns;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.TextView;
import android.os.Handler;

public class TestJmDNS extends Activity {

    WifiManager.MulticastLock lock;
    Handler handler = new Handler();
    TextView log;

    static final int PORT = 5431;
    static final String TYPE = "_myprotocol._tcp.local.";
    Thread srvThread;
    ServerSocket server;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        log = (TextView) findViewById(R.id.text);

        srvThread = new Thread(new Runnable() {
            public void run() {
                try {
                    server = new ServerSocket();
                    server.bind(new InetSocketAddress(getIpAddr(), PORT));
                    notifyUser("Server started");
                    Socket client = server.accept(); // Останавливает поток до подключения клиента
                    if (!Thread.interrupted() && client != null) { // Если поток не остановлен и все Ок, то читаем данные от клиента
                        notifyUser("Client connected to my server");
                        DataInputStream dis = new DataInputStream(client.getInputStream());
                        String msg = dis.readUTF();
                        notifyUser("Receive message from client: " + msg);
                        client.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (!server.isClosed()) {
                        try {
                            server.close();
                        } catch (IOException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
        srvThread.start();
        /* В фоновом потоке, потому что работа с сетью не должна проводиться в UI-thread */
        new Thread(new Runnable() {
            public void run() {
                try {
                    /* Ждем пока стартует сервер, если планируется взаимодействие с ним 
                     * на этом же устройстве, в данном случае не обязательно */
                    srvThread.join(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                setUp();
            }
        }).start();

    }

    private JmDNS jmdns;
    private ServiceListener listener;
    private ServiceInfo serviceInfo;

    private void setUp() {
        final WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        /* Следующий код включает поддержку multicast в приложении, она отключена по умолчанию.
         * Без него JmDNS не будет работать */
        lock = wifi.createMulticastLock("mylock");
        lock.setReferenceCounted(true);
        lock.acquire();
        try {
        	/* Создаем JmDNS и слушатель событий для него */
            InetAddress deviceIpAddress = InetAddress.getByName(getIpAddr(wifi));
            jmdns = JmDNS.create(deviceIpAddress, "MyAndroidHostName");
            jmdns.addServiceListener(TYPE, listener = new ServiceListener() {

                @Override
                public void serviceResolved(ServiceEvent ev) {
                    notifyUser("Service resolved: " + ev.getInfo().getQualifiedName() + " port:" + ev.getInfo().getPort());
                    /* Если сервис запущен на этом же устройстве, то пропускаем его */
                    if (getIpAddr(wifi).equals(ev.getInfo().getInetAddresses()[0].getCanonicalHostName())) {
                        return;
                    }
                    Socket client = null;
                    try {
                    	/* Обнаружен сервер, шлем ему данные с помощью Socket */
                        String url = ev.getInfo().getInetAddresses()[0].getCanonicalHostName() + ":" + ev.getInfo().getPort();
                        notifyUser("Create client to " + url);
                        client = new Socket(ev.getInfo().getInetAddresses()[0], ev.getInfo().getPort());
                        notifyUser("Client created to " + url);
                        DataOutputStream dos = new DataOutputStream(client.getOutputStream());
                        dos.writeUTF("Hello from " + android.os.Build.MODEL + "!!!");
                        notifyUser("Client sends message to " + url);
                        dos.flush();
                        dos.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    } finally {
                        if (client != null && !client.isClosed()) {
                            try {
                                client.close();
                            } catch (IOException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                        }
                    }
                }

                @Override
                public void serviceRemoved(ServiceEvent ev) {
                    notifyUser("Service removed: " + ev.getName());
                }

                @Override
                public void serviceAdded(ServiceEvent event) {
                    // Для вызова serviceResolved(...)
                    jmdns.requestServiceInfo(event.getType(), event.getName(), 1 /* timeout 1ms*/);
                }
            });
            /* Создаем наш сервис, теперь другие устройства его смогут найти по протоколу multicast DNS (mDNS) */
            serviceInfo = ServiceInfo.create(TYPE, "AndroidTest", PORT, "My android server");
            jmdns.registerService(serviceInfo);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void notifyUser(final String msg) {
        handler.postDelayed(new Runnable() {
            public void run() {
                log.setText(log.getText() + "\n" + msg);
            }
        }, 1);

    }

    @Override
    protected void onStop() {
    	/* Когда activity останавливается, все закрываем */
        if (jmdns != null) {
            if (listener != null) {
                jmdns.removeServiceListener(TYPE, listener);
                listener = null;
            }
            jmdns.unregisterAllServices();
            try {
                jmdns.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            jmdns = null;
        }
        srvThread.interrupt();
        if (server != null && !server.isClosed()) {
            try {
            	/* Если сервер все еще ожидает клиента (висит на методе accept()
            	 * то метод close() прервет его вызвав исключение */
                server.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        if (lock != null) {
            lock.release();
            lock = null;
        }
        super.onStop();
    }

    public static String getIpAddr(WifiManager wifiManager) {
        int ip = wifiManager.getConnectionInfo().getIpAddress();
        return String.format(
                "%d.%d.%d.%d",
                (ip & 0xff),
                (ip >> 8 & 0xff),
                (ip >> 16 & 0xff),
                (ip >> 24 & 0xff));
    }

    public String getIpAddr() {
        return getIpAddr((WifiManager) getSystemService(Context.WIFI_SERVICE));
    }
}
