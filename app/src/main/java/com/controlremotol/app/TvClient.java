package com.controlremotol.app;

import android.content.SharedPreferences;
import android.util.Base64;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TvClient {
    interface Observer { void status(String state, String detail); }
    private final SharedPreferences prefs;
    private final Observer observer;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger nextId = new AtomicInteger(2);
    private final AtomicBoolean motionQueued = new AtomicBoolean(false);
    private final Object motionLock = new Object();
    private double pendingX, pendingY;
    private volatile MiniWebSocket primary, pointer;
    private volatile String brand = "LG", ip = "";
    private volatile boolean ready = false;
    private volatile int generation = 0;
    private int samsungX = 960, samsungY = 540;
    // 2018 Samsung sets may accept virtual keys but silently ignore virtual mouse.
    // NAV mode translates hand gestures into real remote direction keys.
    private volatile boolean samsungNavigationMode = true;
    private double navigationX = 0, navigationY = 0;
    private long navigationLastCommand = 0;

    void setCursorMode(String mode) {
        samsungNavigationMode = !"POINTER".equals(mode);
        synchronized (motionLock) {
            navigationX = navigationY = pendingX = pendingY = 0;
            navigationLastCommand = 0;
        }
        if (ready && "SAMSUNG".equals(brand)) {
            observer.status("connected", samsungNavigationMode
                ? "Samsung conectada · Movimiento = flechas sin clic; botón grande = OK."
                : "Samsung conectada · Puntero experimental; puede no funcionar.");
        }
    }

    TvClient(SharedPreferences prefs, Observer observer) {
        this.prefs = prefs;
        this.observer = observer;
    }
    void connect(String requestedBrand, String host) {
        if (!localIPv4(host)) { observer.status("error", "Escribe una dirección IP local válida, por ejemplo 192.168.1.20"); return; }
        brand = requestedBrand.equals("SAMSUNG") ? "SAMSUNG" : "LG";
        ip = host;
        int cycle = ++generation;
        ready = false;
        synchronized (motionLock) {
            navigationX = navigationY = pendingX = pendingY = 0;
            navigationLastCommand = 0;
        }
        observer.status("connecting", "Buscando televisor en " + ip + "…");
        io.execute(() -> {
            discardSockets();
            if (cycle != generation) return;
            try {
                if (brand.equals("LG")) connectLG(cycle);
                else connectSamsung(cycle);
            } catch (Exception ex) {
                if (cycle == generation) observer.status("error", "No se pudo conectar. Revisa la IP, el Wi-Fi y el permiso de la TV.");
            }
        });
    }
    private static boolean localIPv4(String address) {
        if (address == null) return false;
        String[] p = address.split("\\.", -1);
        if (p.length != 4) return false;
        int[] parts = new int[4];
        try { for(int i=0;i<4;i++) {
            if(p[i].isEmpty() || p[i].length()>3) return false;
            parts[i]=Integer.parseInt(p[i]);
            if(parts[i]<0 || parts[i]>255) return false;
        }} catch(NumberFormatException ex) { return false; }
        return parts[0]==10 || (parts[0]==192 && parts[1]==168) ||
          (parts[0]==172 && parts[1]>=16 && parts[1]<=31);
    }
    private void connectLG(int cycle) throws Exception {
        Exception last = null;
        for (String url : new String[]{"wss://"+ip+":3001/", "ws://"+ip+":3000/"}) {
            try {
                MiniWebSocket sock = MiniWebSocket.open(url, new MiniWebSocket.Listener() {
                    @Override public void onText(String text) { io.execute(() -> onLgMessage(cycle,text)); }
                    @Override public void onClose(String reason) { io.execute(() -> closed(cycle,reason)); }
                });
                if (cycle != generation) { sock.close(); return; }
                primary = sock;
                JSONObject manifest = new JSONObject();
                manifest.put("manifestVersion", 1);
                manifest.put("permissions", new org.json.JSONArray()
                    .put("CONTROL_MOUSE_AND_KEYBOARD")
                    .put("CONTROL_INPUT_TEXT")
                    .put("CONTROL_AUDIO")
                    .put("CONTROL_POWER")
                    .put("CONTROL_INPUT_TV")
                    .put("READ_INSTALLED_APPS")
                    .put("LAUNCH"));
                JSONObject payload = new JSONObject();
                payload.put("forcePairing", false);
                payload.put("pairingType", "PROMPT");
                payload.put("manifest", manifest);
                String key = prefs.getString("lg_key_"+ip, "");
                if (!key.isEmpty()) payload.put("client-key",key);
                JSONObject register = new JSONObject();
                register.put("type","register");
                register.put("id","register_1");
                register.put("payload",payload);
                sock.send(register.toString());
                observer.status("pairing", "Acepta la solicitud de conexión en la TV LG.");
                return;
            } catch (Exception e) { last = e; }
        }
        if (last != null) throw last;
    }
    private void onLgMessage(int cycle, String text) {
        if (cycle != generation) return;
        try {
            JSONObject j = new JSONObject(text);
            String type = j.optString("type", "");
            String id = j.optString("id", "");
            JSONObject payload = j.optJSONObject("payload");
            if ("registered".equals(type)) {
                if (payload != null) {
                    String clientKey = payload.optString("client-key","");
                    if (!clientKey.isEmpty()) prefs.edit().putString("lg_key_"+ip,clientKey).apply();
                }
                ready = true;
                observer.status("connected", "LG conectada. Preparando el cursor…");
                lgRequest("ssap://com.webos.service.networkinput/getPointerInputSocket",null,"pointer_1");
            } else if ("response".equals(type) && "pointer_1".equals(id)) {
                String socketPath = payload == null ? "" : payload.optString("socketPath","");
                if (socketPath.isEmpty()) {
                    observer.status("connected", "LG conectada; las flechas están disponibles.");
                    return;
                }
                try {
                    URI uri = new URI(socketPath);
                    if (!ip.equals(uri.getHost())) {
                        observer.status("connected", "LG conectada; el cursor no está disponible en esta TV.");
                        return;
                    }
                    MiniWebSocket point = MiniWebSocket.open(socketPath, new MiniWebSocket.Listener() {
                        @Override public void onText(String msg) {}
                        @Override public void onClose(String reason) { }
                    });
                    pointer = point;
                    observer.status("connected", "LG lista. Puedes mover el cursor y escribir.");
                } catch(Exception e) { observer.status("connected", "LG conectada; prueba las flechas si el cursor no responde."); }
            } else if ("error".equals(type)) {
                String msg = j.optString("error", "Error en el televisor");
                if ("register_1".equals(id)) {
                    ready = false;
                    observer.status("error", "Emparejamiento rechazado: " + msg);
                } else if ("pointer_1".equals(id)) {
                    observer.status("connected", "LG conectada, pero este firmware no permite el cursor.");
                }
            }
        } catch (Exception ignored) { }
    }
    private void lgRequest(String uri, JSONObject payload, String id) throws Exception {
        if (primary == null) return;
        JSONObject packet = new JSONObject();
        packet.put("type","request");
        packet.put("id",id);
        packet.put("uri",uri);
        if (payload != null) packet.put("payload",payload);
        primary.send(packet.toString());
    }
    private void connectSamsung(int cycle) throws Exception {
        String name = Base64.encodeToString("Control Remoto L".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String token = prefs.getString("samsung_token_"+ip, "");
        String query = "?name="+name+(token.isEmpty()?"":"&token="+token);
        Exception last = null;
        for (String url : new String[]{"wss://"+ip+":8002/api/v2/channels/samsung.remote.control"+query,
                  "ws://"+ip+":8001/api/v2/channels/samsung.remote.control"+query}) {
            try {
                MiniWebSocket sock = MiniWebSocket.open(url, new MiniWebSocket.Listener() {
                    @Override public void onText(String text) { io.execute(() -> onSamsungMessage(cycle,text)); }
                    @Override public void onClose(String reason) { io.execute(() -> closed(cycle,reason)); }
                });
                if (cycle != generation) { sock.close(); return; }
                primary = sock;
                observer.status("pairing", "Autoriza «Control Remoto L» en tu Samsung si aparece el aviso.");
                return;
            } catch(Exception ex) { last = ex; }
        }
        if (last != null) throw last;
    }
    private void onSamsungMessage(int cycle, String text) {
        if (cycle != generation) return;
        try {
            JSONObject obj = new JSONObject(text);
            String event = obj.optString("event","");
            if ("ms.channel.connect".equals(event)) {
                JSONObject data = obj.optJSONObject("data");
                String token = data == null ? "" : data.optString("token","");
                if(!token.isEmpty()) prefs.edit().putString("samsung_token_"+ip,token).apply();
                ready = true;
                observer.status("connected", samsungNavigationMode
                    ? "Samsung conectada · Movimiento = flechas, clic = OK."
                    : "Samsung conectada · Puntero experimental; puede no funcionar.");
            } else if ("ms.channel.unauthorized".equals(event)) {
                ready = false;
                observer.status("error", "Acceso denegado por la Samsung.");
            }
        } catch (Exception ignored) {}
    }
    private void closed(int cycle,String reason) {
        if (cycle == generation && primary != null && !ready) {
            observer.status("error", "La TV cerró la conexión. Autorízala y vuelve a conectar.");
        } else if (cycle == generation && ready) {
            ready = false;
            observer.status("error", "Conexión perdida. Toca Conectar nuevamente.");
        }
    }

    void button(String name) {
        io.execute(() -> {
            if (!ready) { observer.status("error", "Primero conecta el televisor."); return; }
            try {
                if (brand.equals("LG")) {
                    if (name.equals("VOLUP") || name.equals("VOLDOWN"))
                        lgRequest(name.equals("VOLUP") ? "ssap://audio/volumeUp" : "ssap://audio/volumeDown", null,"cmd_"+nextId.getAndIncrement());
                    else if (name.equals("POWER")) lgRequest("ssap://system/turnOff",null,"cmd_"+nextId.getAndIncrement());
                    else if (name.equals("TEXT_ENTER"))
                        lgRequest("ssap://com.webos.service.ime/sendEnterKey",null,"cmd_"+nextId.getAndIncrement());
                    else if (name.equals("DELETE")) {
                        JSONObject p = new JSONObject(); p.put("count",1);
                        lgRequest("ssap://com.webos.service.ime/deleteCharacters",p,"cmd_"+nextId.getAndIncrement());
                    }
                    else if (name.equals("CHUP") || name.equals("CHDOWN"))
                        lgRequest(name.equals("CHUP") ? "ssap://tv/channelUp" : "ssap://tv/channelDown",null,"cmd_"+nextId.getAndIncrement());
                    else if(pointer != null) pointer.send("type:button\nname:"+name+"\n\n");
                    else observer.status("connected", "La TV no habilitó los botones. Vuelve a conectar.");
                } else {
                    String k = name.equals("TEXT_ENTER") || name.equals("ENTER") ? "KEY_ENTER" : "KEY_"+name;
                    samsungCommand("SendRemoteKey","Click",k);
                }
            } catch(Exception e) { observer.status("error", "No se pudo enviar el botón."); }
        });
    }

    void click() {
        if ("SAMSUNG".equals(brand) && samsungNavigationMode) {
            button("ENTER");
            return;
        }
        io.execute(() -> {
            if (!ready) return;
            try {
                if (brand.equals("LG")) {
                    if (pointer != null) pointer.send("type:click\n\n");
                } else {
                    JSONObject pos = new JSONObject();
                    pos.put("x",samsungX).put("y",samsungY).put("Time","0");
                    JSONObject p = new JSONObject();
                    p.put("Cmd","LeftClick").put("Position",pos).put("TypeOfRemote","ProcessMouseDevice");
                    samsungSend(p);
                }
            } catch(Exception ignored) {}
        });
    }

    void move(double dx, double dy) {
        if (!ready || !Double.isFinite(dx) || !Double.isFinite(dy)) return;
        if ("SAMSUNG".equals(brand) && samsungNavigationMode) {
            moveSamsungSelection(dx, dy);
            return;
        }
        synchronized (motionLock) { pendingX += dx; pendingY += dy; }
        if (motionQueued.compareAndSet(false, true)) {
            io.execute(() -> {
                double x,y;
                synchronized(motionLock) { x=pendingX; y=pendingY; pendingX=pendingY=0; }
                motionQueued.set(false);
                if (!ready) return;
                int ix = (int)Math.max(-150,Math.min(150,Math.round(x)));
                int iy = (int)Math.max(-150,Math.min(150,Math.round(y)));
                if (ix==0 && iy==0) return;
                try {
                    if (brand.equals("LG")) {
                        if (pointer != null) pointer.send("type:move\ndx:"+ix+"\ndy:"+iy+"\ndown:0\n\n");
                    } else {
                        samsungX = Math.max(0,Math.min(1919,samsungX+ix));
                        samsungY = Math.max(0,Math.min(1079,samsungY+iy));
                        JSONObject pos = new JSONObject();
                        pos.put("x",samsungX).put("y",samsungY).put("Time","0");
                        JSONObject p = new JSONObject();
                        p.put("Cmd","Move").put("Position",pos).put("TypeOfRemote","ProcessMouseDevice");
                        samsungSend(p);
                    }
                } catch(Exception ignored) {}
            });
        }
    }

    // Convert accumulated touchpad/gyro movement to one directional key per gesture.
    // Threshold avoids jitter and cooldown prevents excessive menu scrolling.
    private void moveSamsungSelection(double dx, double dy) {
        String direction;
        synchronized (motionLock) {
            navigationX = Math.max(-400, Math.min(400, navigationX + dx));
            navigationY = Math.max(-400, Math.min(400, navigationY + dy));
            long now = SystemClock.elapsedRealtime();
            if (now - navigationLastCommand < 450) return;
            if (Math.max(Math.abs(navigationX), Math.abs(navigationY)) < 165) return;
            if (Math.abs(navigationX) >= Math.abs(navigationY)) {
                direction = navigationX < 0 ? "LEFT" : "RIGHT";
            } else {
                direction = navigationY < 0 ? "UP" : "DOWN";
            }
            navigationX = navigationY = 0;
            navigationLastCommand = now;
        }
        sendSamsungDirectionWithoutClick(direction);
    }

    // Gyroscope/touchpad movement must never activate OK or send a mouse click.
    // Samsung's Cmd=Click is a *key press*, but using Press/Release here keeps
    // the navigation path explicitly separate from the user-only select button.
    private void sendSamsungDirectionWithoutClick(String direction) {
        if (!("LEFT".equals(direction) || "RIGHT".equals(direction) ||
              "UP".equals(direction) || "DOWN".equals(direction))) return;
        io.execute(() -> {
            if (!ready || !"SAMSUNG".equals(brand) || !samsungNavigationMode) return;
            try {
                String key = "KEY_" + direction;
                samsungCommand("SendRemoteKey", "Press", key);
                samsungCommand("SendRemoteKey", "Release", key);
            } catch(Exception ex) {
                observer.status("error", "No se pudo mover la selección.");
            }
        });
    }

    void text(String value) {
        if (value == null || value.isEmpty()) return;
        if (value.length() > 1000) { observer.status("error","Máximo 1000 caracteres por envío."); return; }
        io.execute(() -> {
            if (!ready) { observer.status("error", "Primero conecta el televisor."); return; }
            try {
                if (brand.equals("LG")) {
                    JSONObject p = new JSONObject();
                    p.put("text",value);
                    p.put("replace",0);
                    lgRequest("ssap://com.webos.service.ime/insertText",p,"text_"+nextId.getAndIncrement());
                } else {
                    JSONObject p = new JSONObject();
                    p.put("Cmd",Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
                    p.put("DataOfCmd","base64");
                    p.put("TypeOfRemote","SendInputString");
                    samsungSend(p);
                }
                observer.status("connected","Texto enviado. Debes tener un campo de escritura abierto en la TV.");
            } catch(Exception ex) { observer.status("error", "La TV no aceptó el texto."); }
        });
    }
    private void samsungCommand(String type, String cmd, String key) throws Exception {
        JSONObject p = new JSONObject();
        p.put("Cmd",cmd).put("DataOfCmd",key).put("Option","false").put("TypeOfRemote",type);
        samsungSend(p);
    }
    private void samsungSend(JSONObject params) throws Exception {
        if(primary == null) return;
        JSONObject j = new JSONObject();
        j.put("method","ms.remote.control");
        j.put("params",params);
        primary.send(j.toString());
    }
    private void discardSockets() {
        if (pointer != null) { pointer.close(); pointer = null; }
        if (primary != null) { primary.close(); primary = null; }
        ready = false;
    }
    void close() {
        ++generation;
        ready = false;
        io.execute(this::discardSockets);
        io.shutdown();
    }
}