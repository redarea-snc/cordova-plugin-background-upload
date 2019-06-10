package com.spoon.backgroundFileUpload;

import android.content.Context;
import android.util.Log;

import com.sromku.simple.storage.SimpleStorage;
import com.sromku.simple.storage.Storage;
import com.sromku.simple.storage.helpers.OrderType;
import net.gotev.uploadservice.MultipartUploadRequest;
import net.gotev.uploadservice.ServerResponse;
import net.gotev.uploadservice.UploadInfo;
import net.gotev.uploadservice.UploadNotificationConfig;
import net.gotev.uploadservice.UploadService;
import net.gotev.uploadservice.UploadServiceBroadcastReceiver;
import net.gotev.uploadservice.okhttp.OkHttpStack;

import okhttp3.OkHttpClient;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class FileTransferBackground extends CordovaPlugin {

  private final String uploadDirectoryName = "FileTransferBackground";
  private Storage storage;
  private CallbackContext uploadCallback;
  private NetworkMonitor networkMonitor;
  private Long lastProgressTimestamp = 0L;
  private HashMap<String,CallbackContext> cancelUploadCallbackMap = new HashMap();
  private boolean hasBeenDestroyed = false;

  private UploadServiceBroadcastReceiver broadcastReceiver = new UploadServiceBroadcastReceiver() {
    @Override
    public void onProgress(Context context, UploadInfo uploadInfo) {

      try {
        Long currentTimestamp = System.currentTimeMillis()/1000;
        if (currentTimestamp - lastProgressTimestamp >=1) {
          LogMessage("id:" + uploadInfo.getUploadId() + " progress: " + uploadInfo.getProgressPercent());
          lastProgressTimestamp = currentTimestamp;

          if (uploadCallback != null && !hasBeenDestroyed) {
            JSONObject objResult = new JSONObject();
            objResult.put("id", uploadInfo.getUploadId());
            objResult.put("progress", uploadInfo.getProgressPercent());
            objResult.put("state", "UPLOADING");
            PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, objResult);
            progressUpdate.setKeepCallback(true);
            uploadCallback.sendPluginResult(progressUpdate);
          }
        }

      } catch (Exception e) {
          e.printStackTrace();
          try{
            sendMessageWithData("onProgressException", "trace", Log.getStackTraceString(e));
          }catch (Exception innerE){
            innerE.printStackTrace();
          }
      }
  }

    @Override
    public void onError(Context context, UploadInfo uploadInfo, final ServerResponse serverResponse, Exception exception) {
      LogMessage("App onError: " + exception);
      if(exception != null){
          exception.printStackTrace();
      }

      try {
        updateStateForUpload(uploadInfo.getUploadId(), UploadState.FAILED, null);

        if (uploadCallback !=null && !hasBeenDestroyed){
          JSONObject errorObj = new JSONObject();
          errorObj.put("id", uploadInfo.getUploadId());
          errorObj.put("error", "execute failed");
          errorObj.put("state", "FAILED");
          String serverResponseText = serverResponse != null ? serverResponse.getBodyAsString() : "NULL";
          errorObj.put("serverResponse", serverResponseText);
          int httpCode = serverResponse != null ? serverResponse.getHttpCode() : 0;
          errorObj.put("statusCode", httpCode);
          Map<String, String> responseHeaders = serverResponse != null ? serverResponse.getHeaders() : null;
          JSONObject headers = new JSONObject();

          if(responseHeaders != null){
              for(String header: responseHeaders.keySet()) {
                  if(header != null && header.length() > 0){
                      String value = responseHeaders.get(header);
                      headers.put(header, value);
                  }
              }
          }

          errorObj.put("headers", headers);
          PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, errorObj);
          errorResult.setKeepCallback(true);
          uploadCallback.sendPluginResult(errorResult);
        }


      } catch (Exception e) {
        e.printStackTrace();
        String errDesc = e != null ? e.getMessage() + "\n" + Log.getStackTraceString(e) : "NULL";
        PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, "Exception: " + errDesc);
        errorResult.setKeepCallback(true);
        if (uploadCallback !=null && !hasBeenDestroyed && FileTransferBackground.this.webView !=null)
            uploadCallback.sendPluginResult(errorResult);
        try{
            sendMessageWithData("onErrorException", "trace", Log.getStackTraceString(e));
        }catch (Exception innerE){
            innerE.printStackTrace();
        }
      }
   }

    @Override
    public void onCompleted(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {

      try {
        LogMessage("server response : " + serverResponse.getBodyAsString() +" for "+uploadInfo.getUploadId());
        updateStateForUpload(uploadInfo.getUploadId(), UploadState.UPLOADED, serverResponse.getBodyAsString());
        if (uploadCallback !=null  && !hasBeenDestroyed){
          JSONObject objResult = new JSONObject();
          objResult.put("id", uploadInfo.getUploadId());
          objResult.put("completed", true);
          objResult.put("serverResponse", serverResponse.getBodyAsString());
          objResult.put("state", "UPLOADED");
          objResult.put("statusCode", serverResponse.getHttpCode());
          JSONObject headers = new JSONObject();
          for(String header: responseHeaders.keySet()) {
              if(header != null && header.length() > 0){
                  String value = responseHeaders.get(header);
                  headers.put(header, value);
              }
          }

          objResult.put("headers", headers);
          PluginResult completedUpdate = new PluginResult(PluginResult.Status.OK, objResult);
          completedUpdate.setKeepCallback(true);
          uploadCallback.sendPluginResult(completedUpdate);
        }

      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    @Override
    public void onCancelled(Context context, UploadInfo uploadInfo) {
      try {
        LogMessage("upload cancelled "+uploadInfo.getUploadId());
        if (hasBeenDestroyed){
          //most likely the upload service was killed by the system
          updateStateForUpload(uploadInfo.getUploadId(), UploadState.FAILED, null);
          return;
        }
        removeUploadInfoFile(uploadInfo.getUploadId());
        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);
        CallbackContext cancelCallback = cancelUploadCallbackMap.get(uploadInfo.getUploadId());
        if (cancelCallback !=null)
          cancelCallback.sendPluginResult(result);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  };

    private void sendMessageWithData(String message, String... data) throws JSONException {
        JSONObject cordovaMessage = new JSONObject();
        cordovaMessage.put("message", message);
        int count = 0;
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> values = new ArrayList<>();
        for(String obj: data){
            if(count % 2 == 0){
                keys.add(obj);
            }
            else{
                values.add(obj);
            }
            count++;
        }

        HashMap<String, String> dataMap = new HashMap<>();
        for(int i=0; i<keys.size(); i++){
            if(i < values.size()){
                dataMap.put(keys.get(i), values.get(i));
            }
        }
        cordovaMessage.put("data", dataMap);
        webView.getPluginManager().postMessage("FileTransferBackground", cordovaMessage);
    }

    @Override
    public void onResume(boolean multitasking) {
        //--Rut Bastoni - 19/02/2019 - namespace HAS TO BE SET BEFORE broadcast receiver is registered, otherwise it won't work
        UploadService.NAMESPACE = cordova.getActivity().getPackageName();
        broadcastReceiver.register(cordova.getActivity());
        try{
            sendMessageWithData("onResume");
        }catch (Exception innerE){
            innerE.printStackTrace();
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        //See https://stackoverflow.com/questions/2682043/how-to-check-if-receiver-is-registered-in-android
        try{
            broadcastReceiver.unregister(cordova.getActivity());
            try{
                sendMessageWithData("onPause");
            }catch (Exception innerE){
                innerE.printStackTrace();
            }
        }catch (IllegalArgumentException e){
            // Simply swallow this unesuful exception
        }
    }

  @Override
  public boolean execute(String action, JSONArray args, final CallbackContext callbackContext){

    try {
      if (action.equalsIgnoreCase("initManager")) {
        uploadCallback = callbackContext;
        this.initManager(args.length() > 0 ? args.get(0).toString() : null, callbackContext);
      } else if (action.equalsIgnoreCase("removeUpload")) {
        this.removeUpload(args.length() > 0 ? args.get(0).toString() : null, callbackContext);
      } else {
        uploadCallback = callbackContext;
        upload(args.length() > 0 ? (JSONObject) args.get(0) : null, uploadCallback);
      }
    } catch (Exception ex) {
      try {
        JSONObject errorObj = new JSONObject();
        errorObj.put("error", ex.getMessage());
        PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, errorObj);
        errorResult.setKeepCallback(true);
        callbackContext.sendPluginResult(errorResult);
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }
    return true;
  }

  private void upload(JSONObject jsonPayload, final CallbackContext callbackContext) throws Exception {
    final FileTransferSettings payload = new FileTransferSettings(jsonPayload.toString());
    if (UploadService.getTaskList().contains(payload.id)){
      LogMessage("upload with id "+payload.id + " is already being uploaded. ignoring re-upload request");
      return;
    }
    LogMessage("adding upload "+payload.id);
    this.createUploadInfoFile(payload.id, jsonPayload);
    if (NetworkMonitor.isConnected) {
      MultipartUploadRequest request = new MultipartUploadRequest(this.cordova.getActivity().getApplicationContext(), payload.id,payload.serverUrl)
        .addFileToUpload(payload.filePath, payload.fileKey)
        .setMaxRetries(0);


      for (String key : payload.parameters.keySet()) {
        request.addParameter(key, payload.parameters.get(key));
      }

      for (String key : payload.headers.keySet()) {
        request.addHeader(key, payload.headers.get(key));
      }

      // Rut - 06/11/2018 - configurazione notifica di upload
      // TODO sarebbe opportuno mettere i messaggi con dei valori di default come parametri di installazione del plugin
      UploadNotificationConfig notificationConfig = new UploadNotificationConfig();
      notificationConfig.getCancelled().autoClear = true;
      UploadNotificationStatusConfig status = notificationConfig.getProgress();

      //--Rut - 06/11/2018 - SU ANDROID OREO (>= 8.0) è DIVENUTO OBBLIGATORIO MOSTRARE UNA NOTIFICA DURANTE L'UTILIZZO
      // DI UN SERVIZIO IN BACKGROUND - configuriamo quindi delle notifiche 'volatili' (che si nascondono da sole finito
      // l'evento) per non impestare la barra delle notifiche utente se ad esempio carica tanti file, e impostiamo i messaggi
      // in italiano
      status.autoClear = true;
      status.message = "Velocità di caricamento [[UPLOAD_RATE]] ([[PROGRESS]])";
      status = notificationConfig.getCompleted();
      status.autoClear = true;
      status.message = "Caricamento completato in [[ELAPSED_TIME]]";
      status = notificationConfig.getError();
      status.autoClear = true;
      status.message = "Caricamento non riuscito";
      status = notificationConfig.getCancelled();
      status.autoClear = true;
      status.message = "Caricamento annullato";
      notificationConfig.setTitleForAllStatuses("Caricamento file");
      request.setNotificationConfig(notificationConfig);
      request.startUpload();
      String callbackId = callbackContext != null ? callbackContext.getCallbackId() : null;
      sendMessageWithData("upload", "jsonPayload", jsonPayload.toString(), "callbackId", callbackId);
    } else {
      LogMessage("Upload failed. Image added to pending list");
      updateStateForUpload(payload.id, UploadState.FAILED, null, null);
      String callbackId = callbackContext != null ? callbackContext.getCallbackId() : null;
      sendMessageWithData(
              "upload failed.Image added to pending list ", "jsonPayload", jsonPayload.toString(), "callbackId", callbackId
      );
    }
  }

  private void LogMessage(String message) {
    Log.d("FileTransferBG", message);
    try{
      sendMessageWithData("LogMessage", "message", message);
    }catch (Exception e){
      e.printStackTrace();
    }
  }

  private void removeUpload(String fileId, CallbackContext callbackContext) {
    try {
      if (fileId == null)
        throw new Exception("missing upload id");
      if (!UploadService.getTaskList().contains(fileId)){
        LogMessage("cancel upload: "+fileId + " which is not in progress, ignoring request");
        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
        return;
      }
      LogMessage("cancel upload "+fileId);
      cancelUploadCallbackMap.put(fileId,callbackContext);
      UploadService.stopUpload(fileId);
        sendMessageWithData("removeUpload", "fileId", fileId, "callbackId", callbackContext.getCallbackId());
      removeUploadInfoFile(fileId);
      PluginResult res = new PluginResult(PluginResult.Status.OK);
      res.setKeepCallback(true);
      callbackContext.sendPluginResult(res);
    } catch (Exception e) {
      e.printStackTrace();
      PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, e.toString());
      errorResult.setKeepCallback(true);
      callbackContext.sendPluginResult(errorResult);
      try{
        sendMessageWithData("removeUploadException", "trace", Log.getStackTraceString(e));
      }catch (Exception innerE){
        innerE.printStackTrace();
      }
    }
  }

  private void stopAllUploads(CallbackContext callbackContext){
    try {
      UploadService.stopAllUploads();
      PluginResult res = new PluginResult(PluginResult.Status.OK);
      res.setKeepCallback(true);
      callbackContext.sendPluginResult(res);
      sendMessageWithData("stopAllUploads", "callbackId", callbackContext.getCallbackId());
    }catch (Exception e) {
      e.printStackTrace();
      PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, e.toString());
      errorResult.setKeepCallback(true);
      callbackContext.sendPluginResult(errorResult);
      try{
        sendMessageWithData("stopAllUploadsException", "trace", Log.getStackTraceString(e));
      }catch (Exception innerE){
        innerE.printStackTrace();
      }
    }
  }

  private void createUploadInfoFile(String fileId, JSONObject upload) {
    try {
      upload.put("createdDate", System.currentTimeMillis() / 1000);
      upload.put("state", UploadState.STARTED);
      storage.createFile(uploadDirectoryName, fileId + ".json", upload.toString());
    } catch (Exception e) {
      e.printStackTrace();
      try{
        sendMessageWithData("createUploadInfoFileException", "trace", Log.getStackTraceString(e));
      }catch (Exception innerE){
        innerE.printStackTrace();
      }
    }
  }

  private void updateStateForUpload(String fileId, String state, String serverResponse, Map<String, String> responseHeaders) {
    try {
      String fileName = fileId + ".json";
      if (!storage.isFileExist(uploadDirectoryName, fileName)){
        LogMessage("could not find "+ fileName + " for updating upload info");
        return;
      }
      String content = storage.readTextFile(uploadDirectoryName, fileName);
      if (content != null) {
        JSONObject uploadJson = new JSONObject(content);
        uploadJson.put("state", state);
        if (state == UploadState.UPLOADED) {
          uploadJson.put("serverResponse", serverResponse != null ? serverResponse : "");

          //--Rut - 27/08/2018 - invia anche i response headers al risultato
          JSONObject headers = new JSONObject();

          if(responseHeaders != null){
            for(String header: responseHeaders.keySet()) {
              if(header != null && header.length() > 0){
                String value = responseHeaders.get(header);
                headers.put(header, value);
              }
            }
          }

          uploadJson.put("headers", headers);
        }
        //delete old file
        removeUploadInfoFile(fileId);
        //write updated file
        storage.createFile(uploadDirectoryName, fileName, uploadJson.toString());
      }
    } catch (Exception e) {
      e.printStackTrace();
      try{
        sendMessageWithData("updateStateForUploadException", "trace", Log.getStackTraceString(e));
      }catch (Exception innerE){
        innerE.printStackTrace();
      }
    }

  }

  private void removeUploadInfoFile(String fileId) {
    storage.deleteFile(uploadDirectoryName, fileId + ".json");
  }

  private ArrayList<JSONObject> getUploadHistory() {
    ArrayList<JSONObject> previousUploads = new ArrayList<JSONObject>();
    try {
      List<File> files = storage.getFiles(uploadDirectoryName, OrderType.DATE);
      for (File file : files) {
        if (file.getName().endsWith(".json")) {
          String content = storage.readTextFile(uploadDirectoryName, file.getName());
          if (content != null) {
            JSONObject uploadJson = new JSONObject(content);
            previousUploads.add(uploadJson);
          }

        }
      }

    } catch (Exception e) {
      e.printStackTrace();
      try{
        sendMessageWithData("getUploadHistoryException", "trace", Log.getStackTraceString(e));
      }catch (Exception innerE){
        innerE.printStackTrace();
      }
    }

    return previousUploads;
  }

  private void initManager(String options, final CallbackContext callbackContext) {
    try {
      Logger.setLogLevel(Logger.LogLevel.DEBUG);
      //--Rut - 08/05/2019 - connessione personalizzata con valori di timeout impostati da noi
      OkHttpClient customClient =  new OkHttpClient.Builder()
              .followRedirects(true)
              .followSslRedirects(true)
              .retryOnConnectionFailure(true)
//              .connectTimeout(15, TimeUnit.SECONDS) // valore di default
              .connectTimeout(10, TimeUnit.SECONDS)
//              .writeTimeout(30, TimeUnit.SECONDS) // valore di default
              .writeTimeout(15, TimeUnit.SECONDS)
//              .readTimeout(30, TimeUnit.SECONDS) // valore di default
              .readTimeout(15, TimeUnit.SECONDS)
              .cache(null)
              .build();

      UploadService.HTTP_STACK = new OkHttpStack(customClient);
      UploadService.UPLOAD_POOL_SIZE = 1;

      storage = SimpleStorage.getInternalStorage(this.cordova.getActivity().getApplicationContext());
      storage.createDirectory(uploadDirectoryName);
      LogMessage("created FileTransfer working directory ");

      if (options != null) {
        //initialised global configuration parameters here
        //JSONObject settings = new JSONObject(options);
      }

      ArrayList<JSONObject> previousUploads = getUploadHistory();
      for (JSONObject upload : previousUploads) {
        String state = upload.getString("state");
        String id = upload.getString("id");

        if (state.equalsIgnoreCase(UploadState.UPLOADED)) {
          JSONObject objResult = new JSONObject();
          objResult.put("id", id);
          objResult.put("completed", true);
          objResult.put("serverResponse", upload.getString("serverResponse"));
          objResult.put("headers", upload.getJSONObject("headers"));
          PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, objResult);
          progressUpdate.setKeepCallback(true);
          callbackContext.sendPluginResult(progressUpdate);

        } else if (state.equalsIgnoreCase(UploadState.FAILED) || state.equalsIgnoreCase(UploadState.STARTED)) {
          //if the state is STARTED, it means app was killed before the upload was completed
          JSONObject errorObj = new JSONObject();
          errorObj.put("id", id);
          errorObj.put("error", "upload failed");
          PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, errorObj);
          errorResult.setKeepCallback(true);
          callbackContext.sendPluginResult(errorResult);
        }
        //delete upload info on disk
        removeUploadInfoFile(id);
      }
      sendMessageWithData("initManager", "options", options, "callbackId", callbackContext.getCallbackId());

      networkMonitor = new NetworkMonitor(webView.getContext(),new ConnectionStatusListener() {
        @Override
        public void connectionDidChange(Boolean isConnected, String networkType){
          LogMessage("detected network change, Connected:" + isConnected);
          uploadPendingList();
        }
      });

    } catch (Exception e) {
      e.printStackTrace();
      try{
        sendMessageWithData("initManagerException", "trace", Log.getStackTraceString(e));
      }catch (Exception innerE){
        innerE.printStackTrace();
      }
    }
  }

  private void uploadPendingList() {
    ArrayList<JSONObject> previousUploads = getUploadHistory();
    for (JSONObject upload : previousUploads) {
      try {
        String state = upload.getString("state");
        if (state.equalsIgnoreCase(UploadState.FAILED) || state.equalsIgnoreCase(UploadState.STARTED)) {
          this.upload(upload, uploadCallback);
        }
      } catch (Exception e) {
        e.printStackTrace();
        try{
          sendMessageWithData("uploadPendingListException", "trace", Log.getStackTraceString(e));
        }catch (Exception innerE){
          innerE.printStackTrace();
        }
      }
    }
  }

  public void onDestroy() {
    LogMessage("plugin onDestroy, unsubscribing all callbacks");
    hasBeenDestroyed = true;
    if (networkMonitor != null)
      networkMonitor.stopMonitoring();
    //broadcastReceiver.unregister(cordova.getActivity().getApplicationContext());
    try{
      sendMessageWithData("onDestroy");
    }catch (Exception innerE){
      innerE.printStackTrace();
    }
  }


}
