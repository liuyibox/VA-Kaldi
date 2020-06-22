package com.lenss.liuyi;

import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import com.lenss.mstorm.communication.internodes.InternodePacket;
import com.lenss.mstorm.communication.internodes.MessageQueues;
import com.lenss.mstorm.topology.Processor;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.log4j.Logger;
import org.kaldi.Assets;
import org.kaldi.KaldiRecognizer;
import org.kaldi.Model;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

//import com.tzutalin.dlib.FaceDet;

import static java.lang.Math.min;

public class MyVoiceConverter extends Processor{

//    static { System.loadLibrary("kaldi_jni"); }
    private Logger logger;
    private final String TAG = "MyVoiceConverter";
    private WeakReference<MyVoiceConverter> mscWeakReference;
    private Model converter_model;
    TextView resultView;

//    private FaceDet dFaceDet = null;

    @Override
    public void prepare(){
        logger = Logger.getLogger(TAG);
//        mscWeakReference = new WeakReference<>(this);
////         We need to be very careful here.
//        mscWeakReference.get().model = new Model("/storage/emulated/0/Android/data/com.lenss.liuyi.edgeassistant/files/sync/model-android");
////        /home/liuyi/Documents/VoiceAssistant_SEC20/EdgeStorm/GAssistant/models/src/main/assets/sync/model-android
//        try{
//            Assets converterAsset = new Assets(mscWeakReference.get());
//            File assetDir = converterAsset.syncAssets();
//            Log.i("!!!!", assetDir.toString());
//            mscWeakReference.get().model = new Model(assetDir.toString()+"/model-android");
//        }catch (IOException e){
//            logger.debug("The model is not accessible in MyVoiceConverter");
//        }
//        System.loadLibrary("android_dlib");
//        dFaceDet = new FaceDet();
//        System.loadLibrary("kaldi_jni");
        converter_model = GAssistantActivity.model;
//        converter_model = new Model("/storage/emulated/0/Android/data/com.lenss.liuyi.edgeassistant/files/sync/model-android");
    }

    @Override
    public void execute(){
//        model = new Model("/storage/emulated/0/Android/data/com.lenss.liuyi.edgeassistant/files/sync/model-android");
//        KaldiRecognizer rec = new KaldiRecognizer(mscWeakReference.get().model, 16000.f);
        KaldiRecognizer rec = new KaldiRecognizer(converter_model, 16000.f);
        while(!Thread.currentThread().interrupted()){

            StringBuilder result = new StringBuilder();
            InternodePacket pktRecv = MessageQueues.retrieveIncomingQueue(getTaskID());
            long enterTime = SystemClock.elapsedRealtimeNanos();
            if(pktRecv != null){
                logger.info("pkt received at mySpeechConverter!");
                byte[] voiceByteArray = pktRecv.complexContent;
                logger.info("This device received voice message, " + getTaskID());
                int left = 0;
                while(left < voiceByteArray.length){
                    int right = min(left + 4096, voiceByteArray.length);
                    byte[] segmentByteArray =  ArrayUtils.subarray(voiceByteArray, left, right);
                    if(rec.AcceptWaveform(segmentByteArray, segmentByteArray.length)){
                        result.append(rec.Result());
                    }else{
                        result.append(rec.PartialResult());
                    }
                    left += 4096;
                }
                result.append(rec.FinalResult());
            }
            byte[] textResultByteArray = result.toString().getBytes();
            InternodePacket pktSend = new InternodePacket();
            pktSend.ID = pktRecv.ID;
            pktSend.type = InternodePacket.TYPE_DATA;
            pktSend.fromTask = getTaskID();
            pktSend.complexContent = textResultByteArray;
            pktSend.traceTask = pktRecv.traceTask;
            pktSend.traceTask.add("MSC_" + getTaskID());
            pktSend.traceTaskEnterTime = pktRecv.traceTaskEnterTime;
            pktSend.traceTaskEnterTime.put("MSC_" + getTaskID(), enterTime);
            pktSend.traceTaskExitTime = pktRecv.traceTaskExitTime;
            long exitTime = SystemClock.elapsedRealtimeNanos();
            pktSend.traceTaskExitTime.put("MSC_" + getTaskID(), exitTime);
            String component = MyVoiceSaver.class.getName();
            try{
                MessageQueues.emit(pktSend, getTaskID(), component);
            } catch (InterruptedException e){
                e.printStackTrace();
            }
        }

    }

    @Override
    public void postExecute(){
//        if(mscWeakReference.get() != null){
//            mscWeakReference.clear();
//        }

//        if(converter_model != null){
//            converter_model.delete();
//        }
    }
}
