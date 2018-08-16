package com.example.motiondetector;

/**
 * Created by 13751 on 2018/3/14.
 */
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
public class MotionDetector {
    public enum GestureType {
        Downstairs,
        Jogging,
        Sitting,
        Standing,
        Upstairs,
        Walking
    }
    public interface Listener {
        void onGestureRecognized(GestureType gestureType);
    }

    private static final String MODEL_FILENAME = "file:///android_asset/frozen_model.pb";

    private static final int GESTURE_DURATION_MS = 10000000; // 2.56 sec
    private static final int GESTURE_SAMPLES = 200;

    private static final String INPUT_NODE = "inputs";
    private static final String OUTPUT_NODE = "y_";
    private static final String[] OUTPUT_NODES = new String[]{OUTPUT_NODE};
    private static final int NUM_CHANNELS = 3;
    private static final long[] INPUT_SIZE = {1, GESTURE_SAMPLES, NUM_CHANNELS};
    private static final String[] labels = new String[]{"Downstairs", "Jogging", "Sitting", "Standing", "Upstairs", "Walking"};

    //private static final float DATA_NORMALIZATION_COEF = 9f;
    //private static final int FILTER_COEF = 20;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler;
    private final float[] TxData=new float[GESTURE_SAMPLES];
    private final float[] TyData=new float[GESTURE_SAMPLES];
    private final float[] TzData=new float[GESTURE_SAMPLES];
    private final float[] outputScores = new float[labels.length];//outputscores数组存放向右，向左的两个概率
    private final float[] recordingData = new float[GESTURE_SAMPLES * NUM_CHANNELS];//recordingData记录的原始数据，线性加速器两个轴的数据128*2个值
    private final float[] recognData = new float[GESTURE_SAMPLES * NUM_CHANNELS ];
    private final float[] inputData=new float[GESTURE_SAMPLES * NUM_CHANNELS ];
    private int dataPos = 0;
    private static List<Float> x=new ArrayList<Float>();
    private static List<Float> y=new ArrayList<Float>();
    private static List<Float> z=new ArrayList<Float>();
    private static List<Float> input_signal=new ArrayList<Float>();
    private TensorFlowInferenceInterface inferenceInterface;
    private HandlerThread sensorHandlerThread;
    private Handler sensorHandler;

    private Thread recognitionThread;
    private final Semaphore recognSemaphore = new Semaphore(0);

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private boolean recStarted;

    public MotionDetector(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start() throws Exception {
        loadTensorflow();//首先加载tensorflow，读取.pb中计算图
        getAccelerometerSensor();//获取手机中线性加速度计传感器

        sensorHandlerThread = new HandlerThread("sensor thread");
        sensorHandlerThread.start();
        sensorHandler = new Handler(sensorHandlerThread.getLooper());

        recognitionThread = new Thread(recognitionRunnable, "recognition thread");
        recognitionThread.start();

        recStarted = sensorManager.registerListener(sensorEventListener, accelerometer,
                GESTURE_DURATION_MS/GESTURE_SAMPLES, sensorHandler);//注册监听，获得加速度计传感器变化值，第三个参数为采样率

        if (!recStarted) {
            sensorHandlerThread.quitSafely();
            recognitionThread.interrupt();

            sensorHandlerThread = null;
            recognitionThread = null;
            sensorHandler = null;

            throw new Exception("registerListener failed. Check that the device has all accelerometer, magnetometer and gyroscope sensors");
        }
    }

    public void stop() {
        if (recStarted) {
            sensorManager.unregisterListener(sensorEventListener);//活动终止时，注销监听

            sensorHandlerThread.quitSafely();
            recognitionThread.interrupt();

            sensorHandlerThread = null;
            recognitionThread = null;
            sensorHandler = null;

            recognSemaphore.tryAcquire(); // restore counter to zero

            recStarted = false;
        }
    }

    public boolean isStarted() {
        return recStarted;
    }

    private void loadTensorflow() {
        if (inferenceInterface == null) {
            inferenceInterface = new TensorFlowInferenceInterface(context.getAssets(), MODEL_FILENAME);
        }
    }

    private void getAccelerometerSensor() throws Exception {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);// to get a reference to the sensor service
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);//确认手机上是否有线性加速度计传感器
        if (accelerometer == null) throw new Exception("No TYPE_ACCELEROMETER sensor found");
    }

    /**
     * called from worker thread
     */
    private final SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            /*synchronized (recordingData) {
                recordingData[dataPos++] = event.values[0];
                recordingData[dataPos++] = event.values[1];
                recordingData[dataPos++] = event.values[2];
                if (dataPos >= recordingData.length) {
                    dataPos = 0;
                }
            }
            // run recognition if recognition thread is available
            if (recognSemaphore.hasQueuedThreads()) recognSemaphore.release();*/
            x.add(event.values[0]);
            y.add(event.values[1]);
            z.add(event.values[2]);
            if(x.size() ==GESTURE_SAMPLES && y.size() == GESTURE_SAMPLES && z.size() == GESTURE_SAMPLES){
                input_signal.addAll(x);
                input_signal.addAll(y);
                input_signal.addAll(z);
                //inputData=toFloatArray(input_signal);
                toFloatArray(input_signal);

                if (recognSemaphore.hasQueuedThreads()){
                    recognSemaphore.release();
                }
            }
        }
    };

    private final Runnable recognitionRunnable = new Runnable() {
        @Override
        public void run() {
            while (true) {
                try {
                    recognSemaphore.acquire();
                    processData();//处理获取的数据，调用tensorflow提供的接口进行姿态识别
                    x.clear();
                    y.clear();
                    z.clear();
                    input_signal.clear();
                    recognSemaphore.release();
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    };

    /**
     * Called from worker thread
     */
    private void processData() {
        // copy recordingData to recognData arranged
        /*synchronized (recordingData) {
            System.arraycopy(recordingData, 0, recognData, recognData.length - dataPos, dataPos);
            System.arraycopy(recordingData, dataPos, recognData, 0, recordingData.length - dataPos);
        }
        for(int i=0;i<GESTURE_SAMPLES;i++)
        {
            TxData[i]=recognData[i*NUM_CHANNELS];
            TyData[i]=recognData[i*NUM_CHANNELS+1];
            TzData[i]=recognData[i*NUM_CHANNELS+2];
        }
        for(int i=0;i<inputData.length;i++)
        {
            if(i<GESTURE_SAMPLES)
            {
                inputData[i]=TxData[i];
            }else if(i<GESTURE_SAMPLES*2)
            {
                inputData[i]=TyData[i-GESTURE_SAMPLES];
            }else
            {
                inputData[i]=TzData[i-GESTURE_SAMPLES*2];
            }
        }*/

            inferenceInterface.feed(INPUT_NODE,inputData , INPUT_SIZE);//利用训练好的模型测试数据
            inferenceInterface.run(OUTPUT_NODES);
            inferenceInterface.fetch(OUTPUT_NODE, outputScores);//经模型计算图计算后输出得到判别为向左，向右两姿势的概率

/* 		// there values are mutually exclusive (i.e. leftProbability + rightProbability = 1)
		float leftProbability = outputScores[0]; // 0..1
		float rightProbability = outputScores[1]; // 0..1

		// convert into independent 0..1 values
		leftProbability -= 0.50; // -0.50..0.50
		leftProbability *= 2; // -1..1
		if (leftProbability < 0) leftProbability = 0;
		rightProbability -= 0.50; // -0.50..0.50
		rightProbability *= 2; // -1..1
		if (rightProbability < 0) rightProbability = 0; */
            detectGestures(outputScores);//根据向左向右姿势的概率得到判别姿势的类型gesturetype
        }

    private static final float RISE_THRESHOLD = 0.9f;
    private static final float FALL_THRESHOLD = 0.5f;
    private static final long MIN_GESTURE_TIME_MS = 400000; // 0.4 sec - the minimum duration of recognized positive signal to be treated as a gesture
    //private static final long GESTURES_DELAY_TIME_MS = 1000000; // 1.0 sec - minimum delay between two gestures
    private long gestureStartTime = -1;
    private GestureType gestureType = null;
    private boolean gestureRecognized = false;

 	private void detectGestures(float Prob[]) {
 	    if (gestureStartTime == -1) {
			// not recognized yet
			if (getHighestProb(Prob) >= RISE_THRESHOLD) {
				gestureStartTime = SystemClock.elapsedRealtimeNanos();
				gestureType = getGestureType(Prob);
			}
		}
		else {//识别完成
			GestureType currType = getGestureType(Prob);
			if ((currType != gestureType) || (getHighestProb(Prob) < FALL_THRESHOLD)) {
				// reset
				gestureStartTime = -1;
				gestureType = null;
				gestureRecognized = false;
			}
			else {
				// gesture continues
				if (!gestureRecognized) {
					long gestureTimeMs = (SystemClock.elapsedRealtimeNanos() - gestureStartTime) / 1000;
					if (gestureTimeMs > MIN_GESTURE_TIME_MS) {
						gestureRecognized = true;
						callListener(gestureType);
					}
				}
			}
		}
	} 

   

    private void callListener(final GestureType gestureType) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onGestureRecognized(gestureType);
                } catch (Throwable ignored) {
                }
            }
        });
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

	private static float getHighestProb(float Prob[])
	{
		float maxProb=Prob[0];
		for(int i=0;i<Prob.length;i++)
		{
			if(Prob[i]>maxProb)
			{
				maxProb=Prob[i];
			}
		}
		return maxProb;
	}	
    private static GestureType getGestureType(float Prob[]) {
        //return (leftProb > RISE_THRESHOLD) ? GestureType.MoveLeft : GestureType.MoveRight;
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

/* 	private static void filterData(float input[], float output[]) {
		Arrays.fill(output, 0);

		float ir = 1.0f / FILTER_COEF;

		for (int i = 0; i < input.length; i += NUM_CHANNELS) {
			for (int j = 0; j < FILTER_COEF; j++) {
				if (i - j * NUM_CHANNELS < 0) continue;
				output[i + 0] += input[i + 0 - j * NUM_CHANNELS] * ir;
				output[i + 1] += input[i + 1 - j * NUM_CHANNELS] * ir;
				//output[i + 2] += input[i + 2 - j * NUM_CHANNELS] * ir;
			}
		}
	}
} */
    private void toFloatArray(List<Float> list)
    {
    int i = 0;
    for (Float f : list) {
        inputData[i++] = (f != null ? f : Float.NaN);
    }
    }
}
