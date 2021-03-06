package pasalab.dfs.perf.benchmark.iterate;

import java.io.IOException;
import java.util.List;

import pasalab.dfs.perf.basic.PerfThread;
import pasalab.dfs.perf.basic.TaskConfiguration;
import pasalab.dfs.perf.benchmark.ListGenerator;
import pasalab.dfs.perf.benchmark.Operators;
import pasalab.dfs.perf.conf.PerfConf;
import pasalab.dfs.perf.fs.PerfFileSystem;

public class IterateThread extends PerfThread {
  protected int mBufferSize;
  protected long mFileLength;
  protected PerfFileSystem mFileSystem;
  protected int mIterations;
  protected int mReadFilesNum;
  protected boolean mShuffle;
  protected String mWorkDir;
  protected int mWriteFilesNum;

  protected double mReadThroughput; // in MB/s
  protected boolean mSuccess;
  protected double mWriteThroughput; // in MB/s

  public double getReadThroughput() {
    return mReadThroughput;
  }

  public boolean getSuccess() {
    return mSuccess;
  }

  public double getWriteThroughput() {
    return mWriteThroughput;
  }

  protected void initSyncBarrier() throws IOException {
    String syncFileName = mTaskId + "-" + mId;
    for (int i = 0; i < mIterations; i ++) {
      mFileSystem.create(mWorkDir + "/sync/" + i + "/" + syncFileName);
    }
  }

  protected void syncBarrier(int iteration) throws IOException {
    String syncDirPath = mWorkDir + "/sync/" + iteration;
    String syncFileName = mTaskId + "-" + mId;
    mFileSystem.delete(syncDirPath + "/" + syncFileName, false);
    while (!mFileSystem.list(syncDirPath).isEmpty()) {
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        LOG.error("Error in Sync Barrier", e);
      }
    }
  }

  public void run() {
    long readBytes = 0;
    long readTimeMs = 0;
    long writeBytes = 0;
    long writeTimeMs = 0;
    mSuccess = true;
    for (int i = 0; i < mIterations; i ++) {
      String dataDir;
      if (mShuffle) {
        dataDir = mWorkDir + "/data/" + i;
      } else {
        dataDir = mWorkDir + "/data/" + mTaskId + "/" + i;
      }

      long tTimeMs = System.currentTimeMillis();
      for (int w = 0; w < mWriteFilesNum; w ++) {
        try {
          String fileName = mTaskId + "-" + mId + "-" + w;
          Operators
              .writeSingleFile(mFileSystem, dataDir + "/" + fileName, mFileLength, mBufferSize);
          writeBytes += mFileLength;
        } catch (IOException e) {
          LOG.error("Failed to write file", e);
          mSuccess = false;
        }
      }
      tTimeMs = System.currentTimeMillis() - tTimeMs;
      writeTimeMs += tTimeMs;

      try {
        syncBarrier(i);
      } catch (IOException e) {
        LOG.error("Error in Sync Barrier", e);
        mSuccess = false;
      }
      tTimeMs = System.currentTimeMillis();
      try {
        List<String> candidates = mFileSystem.list(dataDir);
        List<String> readList = ListGenerator.generateRandomReadFiles(mReadFilesNum, candidates);
        for (String fileName : readList) {
          readBytes += Operators.readSingleFile(mFileSystem, fileName, mBufferSize);
        }
      } catch (Exception e) {
        LOG.error("Failed to read file", e);
        mSuccess = false;
      }
      tTimeMs = System.currentTimeMillis() - tTimeMs;
      readTimeMs += tTimeMs;
    }
    mReadThroughput = (readBytes / 1024.0 / 1024.0) / (readTimeMs / 1000.0);
    mWriteThroughput = (writeBytes / 1024.0 / 1024.0) / (writeTimeMs / 1000.0);
  }

  @Override
  public boolean setupThread(TaskConfiguration taskConf) {
    mBufferSize = taskConf.getIntProperty("buffer.size.bytes");
    mFileLength = taskConf.getLongProperty("file.length.bytes");
    mIterations = taskConf.getIntProperty("iterations");
    mReadFilesNum = taskConf.getIntProperty("read.files.per.thread");
    mShuffle = taskConf.getBooleanProperty("shuffle.mode");
    mWorkDir = taskConf.getProperty("work.dir");
    mWriteFilesNum = taskConf.getIntProperty("write.files.per.thread");
    try {
      mFileSystem = Operators.connect(PerfConf.get().DFS_ADDRESS, taskConf);
      initSyncBarrier();
    } catch (IOException e) {
      LOG.error("Failed to setup thread, task " + mTaskId + " - thread " + mId, e);
      return false;
    }
    mSuccess = false;
    mReadThroughput = 0;
    mWriteThroughput = 0;
    return true;
  }

  @Override
  public boolean cleanupThread(TaskConfiguration taskConf) {
    try {
      Operators.close(mFileSystem);
    } catch (IOException e) {
      LOG.warn("Error when close file system, task " + mTaskId + " - thread " + mId, e);
    }
    return true;
  }

}
