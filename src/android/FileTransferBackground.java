package com.spoon.backgroundFileUpload;

import android.content.Context;
import android.util.Log;

import com.sromku.simple.storage.SimpleStorage;
import com.sromku.simple.storage.Storage;
import com.sromku.simple.storage.helpers.OrderType;

import net.gotev.uploadservice.*;
import net.gotev.uploadservice.okhttp.OkHttpStack;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FileTransferBackground extends CordovaPlugin {

  private final String uploadDirectoryName = "FileTransferBackground";
  private Storage storage;
  private CallbackContext uploadCallback;
  private NetworkMonitor networkMonitor;
  private Long lastProgressTimestamp = 0L;

  @Override
  protected void pluginInitialize() {
    UploadService.NAMESPACE = cordova.getActivity().getPackageName();
  }

  @Override
  public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {

    try {
      if (action.equalsIgnoreCase("initManager")) {
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
    this.createUploadInfoFile(payload.id, jsonPayload);
    final FileTransferBackground self = this;
    if (NetworkMonitor.isConnected) {
      MultipartUploadRequest request = new MultipartUploadRequest(this.cordova.getActivity().getApplicationContext(), payload.id,payload.serverUrl)
        .addFileToUpload(payload.filePath, payload.fileKey)
        .setMaxRetries(0)
        .setDelegate(new UploadStatusDelegate() {
          @Override
          public void onProgress(Context context, UploadInfo uploadInfo) {

            try {
              Long currentTimestamp = System.currentTimeMillis()/1000;
              if (currentTimestamp - lastProgressTimestamp >=1) {
                LogMessage("id:" + payload.id + " progress: " + uploadInfo.getProgressPercent());
                lastProgressTimestamp = currentTimestamp;
                JSONObject objResult = new JSONObject();
                objResult.put("id", payload.id);
                objResult.put("progress", uploadInfo.getProgressPercent());
                objResult.put("state", "UPLOADING");
                PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, objResult);
                progressUpdate.setKeepCallback(true);
                if (callbackContext != null && self.webView != null)
                  callbackContext.sendPluginResult(progressUpdate);
              }

            } catch (Exception e) {
              e.printStackTrace();
            }
          }

          @Override
          public void onError(Context context, UploadInfo uploadInfo, ServerResponse serverResponse, Exception exception) {
            LogMessage("App onError: " + exception);
            exception.printStackTrace();

            try {
              updateStateForUpload(payload.id, UploadState.FAILED, null, null);

              JSONObject errorObj = new JSONObject();
              errorObj.put("id", payload.id);
              errorObj.put("error", "execute failed");
              //--Rut Bastoni - 24/08/2018 - add an extra-field with error trace
              errorObj.put("errorDetail", exception.getMessage() + "\n" + Log.getStackTraceString(exception));
              errorObj.put("state", "FAILED");
              errorObj.put("serverResponse", serverResponse.getBodyAsString());
              errorObj.put("statusCode", serverResponse.getHttpCode());
              Map<String, String> responseHeaders = serverResponse.getHeaders();
              JSONObject headers = new JSONObject();
              for(String header: responseHeaders.keySet()) {
                if(header != null && header.length() > 0){
                  String value = responseHeaders.get(header);
                  headers.put(header, value);
                }
              }
              errorObj.put("headers", headers);
              PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, errorObj);
              errorResult.setKeepCallback(true);
              if (callbackContext !=null  && self.webView !=null )
                callbackContext.sendPluginResult(errorResult);

            } catch (Exception e) {
              e.printStackTrace();
            }
          }

          @Override
          public void onCompleted(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {

            try {
              LogMessage("server response : " + serverResponse.getBodyAsString());
              //--Rut - 27/08/2018 - invia anche i response headers al risultato
              Map<String, String> responseHeaders = serverResponse.getHeaders();
              updateStateForUpload(payload.id, UploadState.UPLOADED, serverResponse.getBodyAsString(), responseHeaders);

              JSONObject objResult = new JSONObject();
              objResult.put("id", payload.id);
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
              if (callbackContext !=null  && self.webView !=null)
                callbackContext.sendPluginResult(completedUpdate);
            } catch (Exception e) {
              e.printStackTrace();
            }
          }

          @Override
          public void onCancelled(Context context, UploadInfo uploadInfo) {
            LogMessage("App cancel");
          }
        });

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

    } else {
      LogMessage("Upload failed. Image added to pending list");
      updateStateForUpload(payload.id, UploadState.FAILED, null, null);
    }
  }

  private void LogMessage(String message) {
    Log.d("FileTransferBG", message);
  }

  private void removeUpload(String fileId, CallbackContext callbackContext) {
    try {
      if (fileId == null)
        return;
      UploadService.stopUpload(fileId);
      removeUploadInfoFile(fileId);
      PluginResult res = new PluginResult(PluginResult.Status.OK);
      res.setKeepCallback(true);
      callbackContext.sendPluginResult(res);
    } catch (Exception e) {
      e.printStackTrace();
      PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, e.toString());
      errorResult.setKeepCallback(true);
      callbackContext.sendPluginResult(errorResult);
    }
  }

  private void createUploadInfoFile(String fileId, JSONObject upload) {
    try {
      upload.put("createdDate", System.currentTimeMillis() / 1000);
      upload.put("state", UploadState.STARTED);

      storage.createFile(uploadDirectoryName, fileId + ".json", upload.toString());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void updateStateForUpload(String fileId, String state, String serverResponse, Map<String, String> responseHeaders) {
    try {
      String content = storage.readTextFile(uploadDirectoryName, fileId + ".json");
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
        storage.createFile(uploadDirectoryName, fileId + ".json", uploadJson.toString());
      }
    } catch (Exception e) {
      e.printStackTrace();
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
    }

    return previousUploads;
  }

  private void initManager(String options, final CallbackContext callbackContext) {
    try {

      UploadService.HTTP_STACK = new OkHttpStack();
      UploadService.UPLOAD_POOL_SIZE = 1;

      storage = SimpleStorage.getInternalStorage(this.cordova.getActivity().getApplicationContext());
      storage.createDirectory(uploadDirectoryName);
      LogMessage("created working directory ");

      networkMonitor = new NetworkMonitor(webView.getContext(),new ConnectionStatusListener() {
        @Override
        public void connectionDidChange(Boolean isConnected, String networkType){
          LogMessage("Connection change, Connected:" + isConnected);
          uploadPendingList();
        }
      });

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

    } catch (Exception e) {
      e.printStackTrace();
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
      }
    }
  }

  public void onDestroy() {
    Log.d("FileTransferBackground"," FileTransferBackground onDestroy");
    if(networkMonitor != null)
      networkMonitor.stopMonitoring();
  }
}
