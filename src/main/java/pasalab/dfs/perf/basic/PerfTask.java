package pasalab.dfs.perf.basic;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import pasalab.dfs.perf.PerfConstants;
import pasalab.dfs.perf.conf.PerfConf;
import pasalab.dfs.perf.fs.PerfFileSystem;

/**
 * The abstract class for all the test tasks. For new test, you should create a new class which
 * extends this.
 */
public abstract class PerfTask {
  protected static final Logger LOG = Logger.getLogger(PerfConstants.PERF_LOGGER_TYPE);

  protected int mId;
  protected String mNodeName;
  protected TaskConfiguration mTaskConf;
  protected String mTaskType;

  private PerfThread[] mThreads;

  public void initialSet(int id, String nodeName, TaskConfiguration taskConf, String taskType) {
    mId = id;
    mNodeName = nodeName;
    mTaskConf = taskConf;
    mTaskType = taskType;
  }

  /**
   * Setup the task. Do some preparations.
   * 
   * @param taskContext The statistics of this task
   * @return true if setup successfully, false otherwise
   */
  protected abstract boolean setupTask(TaskContext taskContext);

  /**
   * Cleanup the task. Do some following work.
   * 
   * @param taskContext The statistics of this task
   * @return true if cleanup successfully, false otherwise
   */
  protected abstract boolean cleanupTask(TaskContext taskContext);

  public boolean setup(TaskContext taskContext) {
    taskContext.setStartTimeMs(System.currentTimeMillis());
    if (this instanceof Supervisible) {
      try {
        PerfFileSystem fs = PerfFileSystem.get(PerfConf.get().DFS_ADDRESS);
        String dfsFailedFilePath = ((Supervisible) this).getDfsFailedPath();
        String dfsReadyFilePath = ((Supervisible) this).getDfsReadyPath();
        String dfsSuccessFilePath = ((Supervisible) this).getDfsSuccessPath();
        if (fs.exists(dfsFailedFilePath)) {
          fs.delete(dfsFailedFilePath, true);
        }
        if (fs.exists(dfsReadyFilePath)) {
          fs.delete(dfsReadyFilePath, true);
        }
        if (fs.exists(dfsSuccessFilePath)) {
          fs.delete(dfsSuccessFilePath, true);
        }
        fs.close();
      } catch (IOException e) {
        LOG.error("Failed to setup Supervisible task", e);
        return false;
      }
    }

    boolean ret = setupTask(taskContext);
    mThreads = new PerfThread[PerfConf.get().THREADS_NUM];
    try {
      for (int i = 0; i < mThreads.length; i ++) {
        mThreads[i] = TaskType.get().getTaskThreadClass(mTaskType);
        mThreads[i].initialSet(i, mId, mNodeName, mTaskType);
        ret &= mThreads[i].setupThread(mTaskConf);
      }
    } catch (Exception e) {
      LOG.error("Error to create task thread", e);
      return false;
    }
    return ret;
  }

  public boolean run(TaskContext taskContext) {
    List<Thread> threadList = new ArrayList<Thread>(mThreads.length);
    try {
      for (int i = 0; i < mThreads.length; i ++) {
        Thread t = new Thread(mThreads[i]);
        threadList.add(t);
      }

      if (this instanceof Supervisible) {
        PerfFileSystem fs = PerfFileSystem.get(PerfConf.get().DFS_ADDRESS);
        String dfsReadyFilePath = ((Supervisible) this).getDfsReadyPath();
        fs.createEmptyFile(dfsReadyFilePath);
        fs.close();
      }

      for (Thread t : threadList) {
        t.start();
      }
      for (Thread t : threadList) {
        t.join();
      }
    } catch (IOException e) {
      LOG.error("Error when run task", e);
      return false;
    } catch (InterruptedException e) {
      LOG.error("Error when wait all threads", e);
      return false;
    } catch (Exception e) {
      LOG.error("Error to create task thread", e);
      return false;
    }
    return true;
  }

  public boolean cleanup(TaskContext taskContext) {
    boolean ret = true;
    for (int i = 0; i < mThreads.length; i ++) {
      ret &= mThreads[i].cleanupThread(mTaskConf);
    }
    ret &= cleanupTask(taskContext);
    taskContext.setFromThread(mThreads);
    taskContext.setFinishTimeMs(System.currentTimeMillis());
    try {
      String outDirPath = PerfConf.get().OUT_FOLDER;
      File outDir = new File(outDirPath);
      if (!outDir.exists()) {
        outDir.mkdirs();
      }
      String reportFileName =
          outDirPath + "/" + PerfConstants.PERF_CONTEXT_FILE_NAME_PREFIX + mTaskType + "-" + mId
              + "@" + mNodeName;
      taskContext.writeToFile(new File(reportFileName));
    } catch (IOException e) {
      LOG.error("Error when generate the task report", e);
      ret = false;
    }

    if (this instanceof Supervisible) {
      try {
        PerfFileSystem fs = PerfFileSystem.get(PerfConf.get().DFS_ADDRESS);
        String dfsFailedFilePath = ((Supervisible) this).getDfsFailedPath();
        String dfsSuccessFilePath = ((Supervisible) this).getDfsSuccessPath();
        if (taskContext.getSuccess() && ret) {
          fs.createEmptyFile(dfsSuccessFilePath);
        } else {
          fs.createEmptyFile(dfsFailedFilePath);
        }
        fs.close();
      } catch (IOException e) {
        LOG.error("Failed to cleanup Supervisible task", e);
        ret = false;
      }
    }
    return ret;
  }
}