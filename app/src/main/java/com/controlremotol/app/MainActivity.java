package com.controlremotol.app;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

public class MainActivity extends Activity implements SensorEventListener {
    private WebView web;
    private TvClient tv;
    private SensorManager sensors;
    private Sensor gyroscope;
    private volatile boolean motionOn = false;
    private volatile float sensitivity = 1.0f;
    private long lastMovement = 0L;
    private long lastSend = 0L;
    private double accumulatedX = 0, accumulatedY = 0;
    private SharedPreferences prefs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("remote-settings", MODE_PRIVATE);
        sensitivity = prefs.getFloat("sensitivity", 1.0f);
        sensors = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        tv = new TvClient(prefs, (code, detail) -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.updateStatus(" + JSONObject.quote(code) + "," + JSONObject.quote(detail) + ")", null);
        }));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(246,248,246));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new RemoteBridge(), "RemoteL");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    public class RemoteBridge {
        @JavascriptInterface public String settings() {
            JSONObject out = new JSONObject();
            try {
                out.put("ip", prefs.getString("ip", ""));
                out.put("brand", prefs.getString("brand", "LG"));
                out.put("sensitivity", prefs.getFloat("sensitivity", 1.0f));
                out.put("gyroAvailable", gyroscope != null);
            } catch (Exception ignored) {}
            return out.toString();
        }
        @JavascriptInterface public void connect(String brand, String ip) {
            prefs.edit().putString("ip", ip.trim()).putString("brand", brand).apply();
            tv.connect(brand, ip.trim());
        }
        @JavascriptInterface public void button(String name) { tv.button(name); }
        @JavascriptInterface public void click() { tv.click(); }
        @JavascriptInterface public void move(double dx, double dy) { tv.move(dx,dy); }
        @JavascriptInterface public void text(String value) { tv.text(value); }
        @JavascriptInterface public void motion(boolean enabled) { runOnUiThread(() -> setMotion(enabled)); }
        @JavascriptInterface public void sensitivity(float value) {
            sensitivity = Math.max(0.2f, Math.min(3.0f, value));
            prefs.edit().putFloat("sensitivity", sensitivity).apply();
        }
    }

    private void setMotion(boolean value) {
        motionOn = value && gyroscope != null;
        accumulatedX = accumulatedY = 0;
        if (sensors != null) {
            sensors.unregisterListener(this);
            if (motionOn) sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
        if (value && gyroscope == null && web != null) {
            web.evaluateJavascript("window.motionUnavailable()", null);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!motionOn || event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastMovement == 0) { lastMovement = now; return; }
        long dt = Math.min(100, now-lastMovement);
        if (dt <= 0) return;
        lastMovement = now;
        // Angular velocity in rad/s; gyro y/x approximate horizontal/vertical air-mouse motion.
        double gain = 33.0 * sensitivity * dt / 30.0;
        double x = event.values[1], y = event.values[0];
        if (Math.abs(x) < 0.055) x = 0;
        if (Math.abs(y) < 0.055) y = 0;
        accumulatedX += -x * gain;
        accumulatedY += -y * gain;
        if (now - lastSend >= 30 && Math.abs(accumulatedX) + Math.abs(accumulatedY) > 0.4) {
            lastSend = now;
            double dx = Math.max(-95, Math.min(95, accumulatedX));
            double dy = Math.max(-95, Math.min(95, accumulatedY));
            tv.move(dx, dy);
            accumulatedX = accumulatedY = 0;
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override protected void onPause() { super.onPause(); if (sensors != null) sensors.unregisterListener(this); lastMovement = 0; }
    @Override protected void onResume() {
        super.onResume(); lastMovement = 0;
        if (motionOn && sensors != null) sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
    }
    @Override protected void onDestroy() {
        setMotion(false);
        tv.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
