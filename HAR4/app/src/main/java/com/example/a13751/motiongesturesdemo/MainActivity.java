package com.example.a13751.motiongesturesdemo;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import android.os.PowerManager;
import android.content.Context;
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    public enum GestureType {
        Downstairs,
        Jogging,
        Sitting,
        Standing,
        Upstairs,
        Walking
    }
    private static final String TAG = MainActivity.class.getSimpleName();//TAG=uk.co.lemberg.motiongesturesdemo.MainActivity

    private ScrollView scrollLogs;//滚动条
    private TextView textLogs;//显示的文本内容，日期+时间+识别姿势
    private static final int N_SAMPLES = 90;
    private static final int GESTURE_DURATION_US=4500000;
    //private static final int N_SAMPLES = 200;
    //private static final int GESTURE_DURATION_US=10000000;
    private static List<Float> x;
    private static List<Float> y;
    private static List<Float> z;

    //private TextToSpeech textToSpeech;
    private float[] results;
    private TensorFlowClassifier classifier;

    private String[] labels = {"Downstairs", "Jogging", "Sitting", "Standing", "Upstairs", "Walking"};

    private DateFormat dateFormat;
    private DateFormat timeFormat;
    private PowerManager.WakeLock mWakeLock;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        x = new ArrayList<>();
        y = new ArrayList<>();
        z = new ArrayList<>();

        scrollLogs = findViewById(R.id.scrollLogs);
        textLogs = findViewById(R.id.textLogs);

        classifier = new TensorFlowClassifier(getApplicationContext());
        getSensorManager().registerListener(this, getSensorManager().getDefaultSensor(Sensor.TYPE_ACCELEROMETER),GESTURE_DURATION_US/N_SAMPLES);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "My Tag");
        mWakeLock.acquire();
        //textToSpeech = new TextToSpeech(this, this);
        //textToSpeech.setLanguage(Locale.US);
    }

    @Override
    protected void onDestroy() {
        getSensorManager().unregisterListener(this);
        mWakeLock.release();
        super.onDestroy();
    }
    /*
    @Override
    protected void onResume() {
        super.onResume();
        getSensorManager().registerListener(this, getSensorManager().getDefaultSensor(Sensor.TYPE_ACCELEROMETER),GESTURE_DURATION_US/N_SAMPLES);
    }*/

    @Override
    public void onSensorChanged(SensorEvent event) {
        activityPrediction();
        x.add(event.values[0]);
        y.add(event.values[1]);
        z.add(event.values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void activityPrediction() {
        if (x.size() == N_SAMPLES && y.size() == N_SAMPLES && z.size() == N_SAMPLES) {
            List<Float> data = new ArrayList<>();
            data.addAll(x);
            data.addAll(y);
            data.addAll(z);

            results = classifier.predictProbabilities(toFloatArray(data));
            GestureType gestureType1=getGestureType(results);
            addLog("Gesture detected: " + gestureType1);

            x.clear();
            y.clear();
            z.clear();
        }
    }

    private float[] toFloatArray(List<Float> list) {
        int i = 0;
        float[] array = new float[list.size()];

        for (Float f : list) {
            array[i++] = (f != null ? f : Float.NaN);
        }
        return array;
    }

    private SensorManager getSensorManager() {
        return (SensorManager) getSystemService(SENSOR_SERVICE);
    }
    public static int getMaxIndex(float arr[]) {
        int maxIndex = 0;   //获取到的最大值的角标
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] > arr[maxIndex]) {
                maxIndex = i;
            }
        }
        return maxIndex;
    }
    private static GestureType getGestureType(float Prob[]) {
        //"Downstairs", "Jogging", "Sitting", "Standing", "Upstairs", "Walking"
        int maxIndex = getMaxIndex(Prob);
        GestureType gestuetype2=null;
        switch (maxIndex) {
            case 0:
                gestuetype2=GestureType.Downstairs;
                break;
            case 1:
                gestuetype2=GestureType.Jogging;
                break;
            case 2:
                gestuetype2=GestureType.Sitting;
                break;
            case 3:
                gestuetype2=GestureType.Standing;
                break;
            case 4:
                gestuetype2=GestureType.Upstairs;
                break;
            case 5:
                gestuetype2= GestureType.Walking;
                break;
        }
        return gestuetype2;
    }
    private DateFormat getDateFormat() {
        if (dateFormat == null) {
            dateFormat = android.text.format.DateFormat.getDateFormat(this);
        }
        return dateFormat;
    }

    private DateFormat getTimeFormat() {
        if (timeFormat == null) {
            timeFormat = android.text.format.DateFormat.getTimeFormat(this);
        }
        return timeFormat;
    }

    private void addLog(String str) {
        Date date = new Date();
        String logStr = String.format("[%s %s] %s\n", getDateFormat().format(date), getTimeFormat().format(date), str);//设置显示格式，日期，时间，识别姿势
        textLogs.append(logStr);
        scrollLogs.fullScroll(View.FOCUS_DOWN);
    }
}
