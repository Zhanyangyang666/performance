package com.xander.performance;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author Xander Wang Created on 2020/12/8.
 * @Description
 */
public class Issue {

  private static String TAG = "_Issue";
  /**
   * 检测 ANR
   */
  public static final int TYPE_ANR = 0;
  /**
   * 检测 FPS
   */
  public static final int TYPE_FPS = 1;
  /**
   * 检测 IPC，进程间通讯
   */
  public static final int TYPE_IPC = 2;
  /**
   * 检测线程的创建
   */
  public static final int TYPE_THREAD = 3;
  /**
   * 检测主线程耗时任务，和 ANR 的检测有些区别
   */
  public static final int TYPE_HANDLER = 4;

  private static ExecutorService saveService = null;

  private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

  /**
   * 类型
   */
  protected int type = -1;
  /**
   * 消息
   */
  protected String msg = "";
  /**
   * 发生的时间
   */
  protected String createTime = "";
  /**
   * 数据
   */
  protected Object data;
  /**
   * byte 数据，用来保持
   */
  protected byte[] dataBuffer;

  public Issue(int type, String msg, Object data) {
    this.type = type;
    this.msg = msg;
    createTime = dateFormat.format(new Date());
    this.data = data;
  }

  public int getType() {
    return type;
  }

  public String getMsg() {
    return msg;
  }

  public Object getData() {
    return data;
  }

  protected String typeToString() {
    String str = null;
    switch (type) {
      case TYPE_ANR:
        str = "ANR";
        break;
      case TYPE_FPS:
        str = "FPS";
        break;
      case TYPE_IPC:
        str = "IPC";
        break;
      case TYPE_THREAD:
        str = "THREAD";
        break;
      case TYPE_HANDLER:
        str = "HANDLER";
        break;
      default:
        str = "NONE";
    }
    return str;
  }

  private void buildIssueString() {
    log(TAG, "start --------------------------------------------------------");
    String dataString = null;
    if (null == dataBuffer) {
      StringBuilder sb = new StringBuilder();
      sb.append("\n=================================================\n");
      sb.append("type: ").append(typeToString()).append('\n');
      sb.append("msg: ").append(msg).append('\n');
      sb.append("create time: ").append(createTime).append('\n');
      buildOtherString(sb);
      if (data instanceof List) {
        sb.append("data:\n");
        buildListString(sb, (List) data);
      } else if (null != data) {
        sb.append("data: ").append(data).append('\n');
      }
      dataString = sb.toString();
      dataBuffer = dataString.getBytes();
      data = null; // 释放，节省内存
      log(TAG, dataString);
    }
    log(TAG, "end ----------------------------------------------------------");
  }

  protected void buildOtherString(StringBuilder sb) {

  }

  protected void buildListString(StringBuilder sb, List dataList) {
    for (int i = 0, len = dataList.size(); i < len; i++) {
      Object item = dataList.get(i);
      sb.append('\t').append(item).append('\n');
    }
  }

  protected void log(String tag, String msg) {
    xLog.w(tag, msg);
  }

  public void print() {
    buildIssueString();
    saveIssue(this);
  }

  static void saveIssue(Issue issue) {
    executorService().execute(new SaveIssueTask(issue));
  }

  static ExecutorService executorService() {
    if (saveService == null) {
      synchronized (Issue.class) {
        if (saveService == null) {
          saveService = Executors.newSingleThreadExecutor();
        }
      }
    }
    return saveService;
  }

  static class SaveIssueTask implements Runnable {

    Issue issue;

    public SaveIssueTask(Issue issue) {
      this.issue = issue;
    }

    @Override
    public void run() {
      MappedByteBuffer buffer = gMappedByteBuffer();
      if (buffer.remaining() < issue.dataBuffer.length) {
        createLogFileAndBuffer();
        buffer = gMappedByteBuffer();
      }
      buffer.put(issue.dataBuffer);
      int dataPosition = buffer.position();
      // xLog.e(TAG, "SaveIssueTask buffer at:" + dataPosition);
      gLineBytes = String.format("%09d", dataPosition).getBytes();
      buffer.position(0);
      buffer.put(gLineBytes);
      buffer.position(dataPosition);
      issue.dataBuffer = null;
    }
  }

  private static final int BUFFER_SIZE = 1024 * 1024;
  private static File gLogFile;
  private static RandomAccessFile gRandomAccessFile;
  private static MappedByteBuffer gBuffer;
  private static byte[] gLineBytes = "000000000".getBytes();
  // log 文件的第一行固定为文件最后字节的位置
  private static int gLineBytesLength = gLineBytes.length;

  protected static MappedByteBuffer gMappedByteBuffer() {
    if (null == gBuffer) {
      initMappedByteBuffer();
    }
    return gBuffer;
  }

  protected static void createLogFileAndBuffer() {
    xLog.e(TAG, "createLogFileAndBuffer gBuffer:" + gBuffer);
    if (null != gBuffer) {
      gBuffer.flip();
      gBuffer = null;
    }
    if (null != gRandomAccessFile) {
      try {
        gRandomAccessFile.close();
      } catch (IOException e) {
        xLog.e(TAG, "gRandomAccessFile IOException", e);
        e.printStackTrace();
      }
      gRandomAccessFile = null;
    }
    if (null != gLogFile) {
      zipLogFile(gLogFile);
      gLogFile = null;
    }
    String fileName = "issues_" + SystemClock.elapsedRealtimeNanos() + ".log";
    gLogFile = new File(ISSUES_ROOT_DIR, fileName);
    if (gLogFile.exists()) {
      gLogFile.delete();
    }
    try {
      gLogFile.createNewFile();
      xLog.e(TAG, "create log file :" + gLogFile.getAbsolutePath());
      gRandomAccessFile = new RandomAccessFile(gLogFile.getAbsolutePath(), "rw");
      gBuffer = gRandomAccessFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, BUFFER_SIZE);
      // 写入 line
      gLineBytes = String.format("%09d", gLineBytesLength).getBytes();
      gBuffer.put(gLineBytes);
    } catch (IOException e) {
      xLog.e(TAG, "gRandomAccessFile IOException", e);
    }
  }

  protected static void initMappedByteBuffer() {
    // 遍历保存文件夹，按照创建时间排序，
    // 只处理 log 文件，然后最后一个 log 文件是上一次创建的，并初始化全局的 log file
    // 其他的 log 文件做压缩处理
    if (saveService == null) {
      saveService = Executors.newSingleThreadExecutor();
    }
    File[] files = ISSUES_ROOT_DIR.listFiles();
    List<File> needZipLogFiles = new ArrayList<>();
    File lastLogFile = null;
    for (int i = 0; i < files.length; i++) {
      File file = files[i];
      if (file.getName().endsWith(".log")) {
        if (lastLogFile == null) {
          lastLogFile = file;
          continue;
        }
        if (lastLogFile.lastModified() < file.lastModified()) {
          needZipLogFiles.add(lastLogFile);
          lastLogFile = file;
        } else {
          needZipLogFiles.add(file);
        }
      }
    }
    xLog.e(TAG, "initMappedByteBuffer lastLogFile:" + lastLogFile);
    for (int i = 0, len = needZipLogFiles.size(); i < len; i++) {
      zipLogFile(needZipLogFiles.get(i));
    }
    if (null != lastLogFile) {
      // 处理 last log file 为全局的 log file
      try {
        gLogFile = lastLogFile;
        gRandomAccessFile = new RandomAccessFile(lastLogFile.getAbsolutePath(), "rw");
        gBuffer = gRandomAccessFile.getChannel()
            .map(FileChannel.MapMode.READ_WRITE, 0, BUFFER_SIZE);
        gBuffer.get(gLineBytes);
        int lastPosition = Integer.parseInt(new String(gLineBytes));
        xLog.e(TAG, "initMappedByteBuffer lastPosition:" + lastPosition);
        if (lastPosition >= BUFFER_SIZE) {
          createLogFileAndBuffer();
        } else {
          gBuffer.position(lastPosition);
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      createLogFileAndBuffer();
    }
  }

  protected static void zipLogFile(final File logFile) {
    // 压缩 log 文件，成功后删除原始 log 文件
    xLog.e(TAG, "zipLogFile:" + logFile);
    executorService().execute(new Runnable() {
      @Override
      public void run() {
        doZipLogFile(logFile);
      }
    });
  }

  static void doZipLogFile(File logFile) {
    File dir = logFile.getParentFile();
    String zipLogFileName = logFile.getName().replace(".log", ".zip");
    File zipLogFile = new File(dir, zipLogFileName);
    if (zipLogFile.exists()) {
      // 清理已经压缩过但是没有删除的 log 文件
      logFile.delete();
      return;
    }
    try {
      FileOutputStream fos = new FileOutputStream(zipLogFile);
      ZipOutputStream zop = new ZipOutputStream(fos);
      FileInputStream fip = new FileInputStream(logFile);
      ZipEntry zipEntry = new ZipEntry(zipLogFileName);
      zop.putNextEntry(zipEntry);
      byte[] bytes = new byte[1024];
      int length;
      while ((length = fip.read(bytes)) >= 0) {
        zop.write(bytes, 0, length);
      }
      fip.close();
      zop.close();
      fos.close();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      logFile.delete();
    }
  }

  protected static String ISSUES_ROOT_DIR_NAME = "issues";
  protected static File ISSUES_ROOT_DIR;

  static void resetTag(String tag) {
    TAG = tag + "_Issue";
  }

  protected static void init(Context context) {
    ISSUES_ROOT_DIR = new File(appSaveFileRootDir(context), ISSUES_ROOT_DIR_NAME);
    ISSUES_ROOT_DIR.mkdirs();
    xLog.e(TAG, "issues save in:" + ISSUES_ROOT_DIR.getAbsolutePath());
  }

  private static File appSaveFileRootDir(Context context) {
    File saveFileDir = null;
    if (null == context) {
      saveFileDir = Environment.getExternalStorageDirectory();
    } else {
      saveFileDir = context.getCacheDir();
    }
    return saveFileDir;
  }
}
