package com.lenss.mstorm.core;

import android.app.Notification;
import androidx.core.app.NotificationCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Process;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.text.style.AbsoluteSizeSpan;
import android.widget.Toast;
import com.google.gson.Gson;
import com.lenss.mstorm.R;
import com.lenss.mstorm.communication.internodes.CommunicationClient;
import com.lenss.mstorm.communication.internodes.CommunicationServer;
import com.lenss.mstorm.communication.internodes.ChannelManager;
import com.lenss.mstorm.communication.internodes.Dispatcher;
import com.lenss.mstorm.communication.internodes.MessageQueues;
import com.lenss.mstorm.executor.Executor;
import com.lenss.mstorm.executor.ExecutorManager;
import com.lenss.mstorm.status.StatusReporter;
import com.lenss.mstorm.status.StatusReporterEKBased;
import com.lenss.mstorm.topology.BTask;
import com.lenss.mstorm.topology.Topology;
import com.lenss.mstorm.utils.GNSServiceHelper;
import com.lenss.mstorm.utils.Serialization;
import com.lenss.mstorm.zookeeper.Assignment;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import edu.tamu.cse.lenss.edgeKeeper.client.EKClient;

import static java.lang.Thread.sleep;

public class ComputingNode extends Service {
    /// LOGGER
    private final String TAG="ComputingNode";
    Logger logger = Logger.getLogger(TAG);

//    static { System.loadLibrary("android_dlib"); }
//    static { System.loadLibrary("kaldi_jni"); }

    // Context
    public static Context context;

    //// SOME CONSTANT STRING
    public static final String ASSIGNMENT = "NEW_ASSIGNMENT";
    public static final String REPORT_ADDRESSES=MStorm.MStormDir+"ReportRecord";
    public static final String EXEREC_ADDRESSES=MStorm.MStormDir+"ExeRecord";

    //// MESSAGE TYPES
    public final int NEW_TASKS = 0;
    public final int SHUTDOWN_TASK = 1;
    public final int SHUTDOWN_COMPUTING_NODE = 2;

    //// EXECUTORS
    private ExecutorManager mExecutorManager;
    private LinkedBlockingDeque<Runnable> mExecutorQueue;
    private final int NUMBER_OF_CORE_EXECUTORS=8;
    private final int NUMBER_OF_MAX_EXECUTORS=16;
    private final int KEEP_ALIVE_TIME = 60;
    private final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

    private static boolean pauseOrContinue = false;

    private StatusReporter statusReporter;  // old version status reporter
    private StatusReporterEKBased statusReporterEKBased;    // new version status reporter using EdgeKeeper
    private Dispatcher dispatcher;

    // processID of computing node
    private static int processID = 0;
    // assignment from scheduler
    private static Assignment assignment;
    // assignment string from scheduler
    private String serAssignment;
    // topology string from scheduler
    private static String serTopology;
    // topology from scheduler
    private static Topology topology;

    private final IBinder mBinder = new LocalBinder();

    //// COMMUNICATION
    // sever and client on computing nodes
    private CommunicationServer mServer;
    private CommunicationClient mClient;
    private volatile int allConnected=0;

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.arg1) {
                case NEW_TASKS:
                    break;
                case SHUTDOWN_TASK:
                    break;
                case SHUTDOWN_COMPUTING_NODE:
                    break;
            }
        }
    }

    public class LocalBinder extends Binder {
        ComputingNode getService() {    // Return this instance of LocalService so clients can call public methods
            return ComputingNode.this;
        }
    }

    @Override
    public void onCreate() {
        //Initial the ExecutorManager
        mExecutorQueue = new LinkedBlockingDeque<Runnable>();
        mExecutorManager = new ExecutorManager(NUMBER_OF_CORE_EXECUTORS, NUMBER_OF_MAX_EXECUTORS, KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT, mExecutorQueue);

/*        HandlerThread thread = new HandlerThread("ServiceStartArguments", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

          // Get the HandlerThread's Looper and use it for our Handler
          mServiceLooper = thread.getLooper();
          mServiceHandler = new ServiceHandler(mServiceLooper); */
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Toast.makeText(this, "service starting", Toast.LENGTH_SHORT).show();

        logger.info("Running in the foreground successfully");

        // record the processID of computing node
        processID = Process.myPid();

        context = getApplicationContext();

        pauseOrContinue = false; // allow pulling more tuple for stream processing

        // get the assignment and local tasks, assign corresponding queues for local tasks
        if(intent==null){
            logger.error("The Intent passed from supervisor is null ...");
            return START_NOT_STICKY;
        }

        serAssignment = intent.getStringExtra(ComputingNode.ASSIGNMENT);
        assignment = new Gson().fromJson(serAssignment, Assignment.class);
        serTopology = assignment.getSerTopology();
        topology = new Gson().fromJson(serTopology, Topology.class);

        // add queues for local tasks
        ArrayList<Integer> localTasks = assignment.getNode2Tasks().get(MStorm.GUID);
        if (localTasks!=null) {
            for (int i = 0; i < localTasks.size(); i++) {
                int taskId = localTasks.get(i);
                MessageQueues.addQueuesForTask(taskId);
            }
        }

//        // record spoutAddress to files
//        ArrayList<String> SpoutAddrs=assignment.getSpoutAddr();
//        String addrs = "";
//        for(String s: SpoutAddrs){
//            String addrIP = GNSServiceHelper.getIPInUseByGUID(s);
//            addrs += addrIP+"\n";
//        }
//        try {
//            FileWriter fw = new FileWriter(SPOUT_ADDRESSES);
//            fw.write(addrs);
//            fw.close();
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }


        // setup server and client for communication with other nodes
        if(mServer != null){
            mServer.release();
        }
        mServer = new CommunicationServer();
        mServer.setup();

        if(mClient != null) {
            mClient.release();
        }
        mClient = new CommunicationClient();
        mClient.setup();

        // wait for setting up of all nodes
//        try {
//            sleep(3000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }

        // Old Method: busy checking, not good
//        ConnectTasks ct = new ConnectTasks();
//        ct.execute(assignment, mClient);
//        while(allConnected==0){     // wait until all workers are connected
//            try {
//                sleep(5);
//                logger.info("Waiting for establishing connections to collaborators ...");
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }

        // New Method: using wait() and notify()
        String waitMsg = "Waiting for establishing connections to collaborators ... ";
        logger.info(waitMsg);
        Supervisor.mHandler.obtainMessage(MStorm.Message_LOG, waitMsg).sendToTarget();
        ConnectTaskThread ctt = new ConnectTaskThread(assignment, mClient);
        ctt.start();
        synchronized (ctt){
            try{
                ctt.wait();
            } catch(InterruptedException e){
                e.printStackTrace();
            }
        }

        if(allConnected==-1){
            String errorMsg = "Cannot establish connections to some collaborators. Please turn off MStorm and G-App, try again ... ";
            logger.error(errorMsg);
            Supervisor.mHandler.obtainMessage(MStorm.Message_LOG, errorMsg).sendToTarget();
            return START_NOT_STICKY;
        } else{
            String successMsg = "Successfully establish connections to all collaborators!";
            logger.info(successMsg);
            Supervisor.mHandler.obtainMessage(MStorm.Message_LOG, successMsg).sendToTarget();
        }

        // execute tasks assigned to this node
        String fileName = assignment.getApk();
        logger.info("Apk name is obtained by the assignment: " + fileName);
        File dexOutputDir = this.getApplicationContext().getFilesDir();
        logger.info("dexOutputDir: " + dexOutputDir.getAbsolutePath());

        // help the DexClassLoader find the .so library
        String librarySearchPath = ((BaseDexClassLoader) getClassLoader()).findLibrary("kaldi_jni");
        logger.info("The library Search Path is: " + librarySearchPath);
        librarySearchPath = librarySearchPath.substring(0, librarySearchPath.lastIndexOf('/'));
        DexClassLoader dcLoader = new DexClassLoader(MStorm.apkFileDirectory + fileName, dexOutputDir.getAbsolutePath(), librarySearchPath, this.getClassLoader());

//        DexClassLoader dcLoader = new DexClassLoader(MStorm.apkFileDirectory + fileName, dexOutputDir.getAbsolutePath(), librarySearchPath, this.getClassLoader());
        logger.info("dexPath: " + MStorm.apkFileDirectory + fileName);
        logger.info("nativeLibPath: " +  MStorm.nativeLibDir);
        if (localTasks!=null) {
//            logger.info("localTask is not null.");
            HashMap<String, String> component2serInstance = topology.getSerInstances();
            HashMap<Integer, String> task2Component = assignment.getTask2Component();
            String sourceAddr  = assignment.getSourceAddr();  // GUID addr
            String sourceIP  = GNSServiceHelper.getIPInUseByGUID(sourceAddr);
            for (int i = 0; i < localTasks.size(); i++) {
                int taskID = localTasks.get(i);
                String component = task2Component.get(taskID);
                String instance = component2serInstance.get(component);
                try {
                    Class<?> mClass = dcLoader.loadClass(component);
                    BTask mTask = (BTask) Serialization.Deserialize(instance, mClass);
                    mTask.setTaskID(taskID);
                    mTask.setComponent(component);
                    mTask.setSourceIP(sourceIP);
                    Executor taskExecutor = new Executor(mTask);
                    mExecutorManager.submitTask(taskID,taskExecutor);
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
//        logger.info("!!!this is after the nativeLibPath: " +  MStorm.nativeLibDir);

        // wait for all tasks running up
//        try {
//            sleep(1000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }

        // start dispatcher
        dispatcher = new Dispatcher();
        Thread dispatchThread = new Thread(dispatcher);
        dispatchThread.setPriority(Thread.MAX_PRIORITY);
        dispatchThread.start();

        // start status reporter
//        statusReporter = StatusReporter.getInstance();
//        statusReporter.initializeStatusReporter();
//        Thread reporterThread = new Thread(statusReporter);
//        reporterThread.setPriority(Thread.MAX_PRIORITY);
//        reporterThread.start();

        // start EdgeKeeper based status reporter
        statusReporterEKBased = StatusReporterEKBased.getInstance();
        Thread reporterThread = new Thread(statusReporterEKBased);
        reporterThread.setPriority(Thread.MAX_PRIORITY);
        reporterThread.start();

        // Start this service as foreground service
        // Start this service as foreground service, adapted from https://stackoverflow.com/questions/47531742/startforeground-fail-after-upgrade-to-android-8-1
//        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
        String CN_NotifiChanId = "com.lenss.storm.computingnode";
        String channelName = "Background Supervisor Service";
        NotificationChannel channel = new NotificationChannel(CN_NotifiChanId, channelName, NotificationManager.IMPORTANCE_NONE);
//            channel.setLightColor(Color.BLUE);
//            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(channel);

        NotificationCompat.Builder builder = new NotificationCompat.Builder (this.getApplicationContext(),CN_NotifiChanId);
        Intent nfIntent = new Intent(this, MStorm.class);
        builder.setContentIntent(PendingIntent.getActivity(this, 0, nfIntent, 0))
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_large))
                .setContentTitle("ComputingNode Is Running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentText("ComputingNode Is Running")
                .setWhen(System.currentTimeMillis());
        Notification notification = builder.build();
//        notification.defaults = Notification.DEFAULT_SOUND;
        startForeground(101, notification);

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        Toast.makeText(this, "Computing Node Shut down", Toast.LENGTH_SHORT).show();

        // stop client and server for communication
        if (mClient != null) {
            mClient.release();
            mClient = null;
        }
        if (mServer != null) {
            mServer.release();
            mServer = null;
        }

        // stop the executors
        List<Runnable> threadsInExecutor = mExecutorManager.shutdownNow();
        for(Runnable thread: threadsInExecutor){
            if(thread instanceof Executor)
                ((Executor) thread).stop();
        }

        try {
            logger.info("==== Wait all threads in executor pool to stop ====");
            Thread.sleep(100);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }

        // release sampling resource
//        if(StatusReporter.getInstance()!=null)
//            StatusReporter.getInstance().stopSampling();

        // stop packet dispatcher
        dispatcher.stopDispatch();

        // stop status reporter
        // statusReporter.stop();
        statusReporterEKBased.stopReport();
        // clear computation status
        MessageQueues.removeTaskQueues();
        // clear channel status
        ChannelManager.releaseChannelsToRemote();

        stopForeground(true);

        super.onDestroy();
    }

    private class ConnectTasks extends AsyncTask<Object, Void, Void> {
//        @Override
//        protected Void doInBackground(Object... objects) {
//            Assignment assignment = (Assignment) objects[0];
//            CommunicationClient mClient = (CommunicationClient) objects[1];
//            ArrayList<Integer> localTasks = assignment.getNode2Tasks().get(MStorm.GUID);
//            HashMap<Integer, MyPair<Integer, Integer>> port2TaskPair = assignment.getPort2TaskPair();
//            HashMap<Integer, String> task2Node = assignment.getTask2Node();
//
//            if (localTasks != null) {
//                for (int i = 0; i < localTasks.size(); i++) {
//                    int taskId = localTasks.get(i);
//                    Iterator<Map.Entry<Integer, MyPair<Integer, Integer>>> it = port2TaskPair.entrySet().iterator();
//                    while (it.hasNext()) {
//                        Map.Entry<Integer, MyPair<Integer, Integer>> entry = it.next();
//                        if (entry.getValue().getL().equals(taskId)) {
//                            int localPort = entry.getKey();
//                            int remoteTaskId = entry.getValue().getR();
//                            String remoteAddress = task2Node.get(remoteTaskId);  // address in GUID
//                            mClient.addlocalPort2RemoteGUID(localPort,remoteAddress);
//                            ChannelFuture cf = mClient.connect(localPort);
//                            cf.awaitUninterruptibly();
//                            Channel currentChannel = cf.getChannel();
//                            if(cf.isSuccess() && currentChannel!=null && currentChannel.isConnected()){
//                                String msg = "A connection from " + currentChannel.getLocalAddress().toString() + " to " + currentChannel.getRemoteAddress().toString() + " succeeds ... ";
//                                logger.debug(msg);
//                            } else{
//                                if(currentChannel!=null){
//                                    currentChannel.close();
//                                }
//                                allConnected = -1;
//                            }
//                        }
//                    }
//                }
//            }
//            allConnected = 1;
//            return null;
//        }

        @Override
        protected Void doInBackground(Object... objects) {
            Assignment assignment = (Assignment) objects[0];
            CommunicationClient mClient = (CommunicationClient) objects[1];
            List<String> assignNodes = assignment.getAssginedNodes();
            int numOfNodes = assignNodes.size();
            String localAddr = MStorm.GUID;
            int indexOfLocalNode;
            if ((indexOfLocalNode = assignNodes.indexOf(localAddr)) != -1){
                int[][] node2NodeConnection = assignment.getNode2NodeConnection();
                for (int i = 0; i < numOfNodes; i++) {
                    if (node2NodeConnection[indexOfLocalNode][i] == 1) {
                        String remoteGUID = assignNodes.get(i);
                        ChannelFuture cf = mClient.connectByGUID(remoteGUID);
                        if(cf!=null){
                            cf.awaitUninterruptibly();
                            Channel currentChannel = cf.getChannel();
                            if (cf.isSuccess() && currentChannel != null && currentChannel.isConnected()) {
                                String msg = "A connection from " + currentChannel.getLocalAddress().toString() + " to " + currentChannel.getRemoteAddress().toString() + " succeeds ... ";
                                logger.debug(msg);
                            } else {
                                if (currentChannel != null) {
                                    currentChannel.close();
                                }
                                allConnected = -1;
                            }
                        }
                    }
                }
            }
            allConnected = 1;
            return null;
        }
    }


    private class ConnectTaskThread extends Thread {
        Assignment assignment;
        CommunicationClient mClient;

        ConnectTaskThread(Assignment assignment, CommunicationClient mClient){
            this.assignment = assignment;
            this.mClient = mClient;
        }

        @Override
        public void run(){
            synchronized (this){
                List<String> assignNodes = assignment.getAssginedNodes();
                int numOfNodes = assignNodes.size();
                String localAddr = MStorm.GUID;
                int indexOfLocalNode;
                int connectionStatus = 1;
                if ((indexOfLocalNode = assignNodes.indexOf(localAddr)) != -1){
                    int[][] node2NodeConnection = assignment.getNode2NodeConnection();
                    for (int i = 0; i < numOfNodes; i++) {
                        if (node2NodeConnection[indexOfLocalNode][i] == 1) {
                            String remoteGUID = assignNodes.get(i);
                            ChannelFuture cf = mClient.connectByGUID(remoteGUID);
                            if(cf!=null){
                                cf.awaitUninterruptibly();
                                Channel currentChannel = cf.getChannel();
                                if (cf.isSuccess() && currentChannel != null && currentChannel.isConnected()) {
                                    String msg = "A connection from " + currentChannel.getLocalAddress().toString() + " to " + currentChannel.getRemoteAddress().toString() + " succeeds ... ";
                                    logger.debug(msg);
                                } else {
                                    connectionStatus = -1;
                                    break;
                                }
                            }
                        }
                    }
                }
                allConnected = connectionStatus;
                notify();
            }
        }
    }

    // get processID of computing node
    public static int getProcessID(){
        return processID;
    }

    public static boolean getPauseOrContinue(){return pauseOrContinue;}

    public static void setPauseOrContinue(boolean p){pauseOrContinue = p;}

    // get assignment
    public static Assignment getAssignment() {
        return assignment;
    }

    public static Topology getTopology(){
        return topology;
    }

}
