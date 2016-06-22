package org.apache.mesos.kafka.plan;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskState;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.kafka.offer.KafkaOfferRequirementProvider;
import org.apache.mesos.kafka.offer.OfferUtils;
import org.apache.mesos.kafka.scheduler.KafkaScheduler;
import org.apache.mesos.kafka.state.FrameworkStateService;
import org.apache.mesos.offer.OfferRequirement;
import org.apache.mesos.offer.TaskRequirement;
import org.apache.mesos.offer.TaskUtils;
import org.apache.mesos.scheduler.plan.Block;
import org.apache.mesos.scheduler.plan.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class KafkaUpdateBlock implements Block {
  private final Log log = LogFactory.getLog(KafkaUpdateBlock.class);

  private Status status = Status.Pending;
  private final KafkaOfferRequirementProvider offerReqProvider;
  private final String targetConfigName;
  private final FrameworkStateService state;
  private final int brokerId;
  private final UUID blockId;
  private final Object taskLock = new Object();
  private List<TaskID> pendingTasks;
  private TaskInfo cachedTaskInfo;

  public KafkaUpdateBlock(
    FrameworkStateService state,
    KafkaOfferRequirementProvider offerReqProvider,
    String targetConfigName,
    int brokerId) {

    this.state = state;
    this.offerReqProvider = offerReqProvider;
    this.targetConfigName = targetConfigName;
    this.brokerId = brokerId;
    this.blockId = UUID.randomUUID();
    this.cachedTaskInfo = getTaskInfo();

    setPendingTasks(getUpdateIds());
    initializeStatus();
  }

  private void setStatus(Status newStatus) {
    Status oldStatus = status;
    status = newStatus;
    log.info(getName() + ": changed status from: " + oldStatus + " to: " + status);
  }

  @Override
  public boolean isPending() {
    return status == Status.Pending;
  }

  @Override
  public boolean isInProgress() {
    return status == Status.InProgress;
  }

  @Override
  public boolean isComplete() {
    return status == Status.Complete;
  }

  @Override
  public OfferRequirement start() {
    log.info("Starting block: " + getName() + " with status: " + Block.getStatus(this));

    if (!isPending()) {
      log.warn("Block is not pending.  start() should not be called.");
      return null;
    }

    if (taskIsRunningOrStaging()) {
      log.info("Adding to restart task list. Block: " + getName() + " Status: " + getTaskStatus());
      KafkaScheduler.restartTasks(getUpdateIds());
      return null;
    }

    try {
      OfferRequirement offerReq = getOfferRequirement();
      setPendingTasks(offerReq);
      return offerReq;
    } catch (Exception e) {
      log.error("Error getting offerRequirement: ", e);
    }

    return null;
  }

  @Override
  public void updateOfferStatus(boolean accepted) {
    if (accepted) {
      setStatus(Status.InProgress);
    } else {
      setStatus(Status.Pending);
    }
  }

  @Override
  public void restart() {
    setStatus(Status.Pending);
  }

  @Override
  public void forceComplete() {
    try {
      List<TaskID> taskIds = Arrays.asList(state.getTaskIdForBroker(brokerId));
      KafkaScheduler.rescheduleTasks(taskIds);
    } catch (Exception ex) {
      log.error("Failed to force completion of Block: " + getId() + "with exception: ", ex);
      return;
    }
  }

  @Override
  public void update(TaskStatus taskStatus) {
    synchronized (taskLock) {
      log.info(getName() + " has pending tasks: " + pendingTasks);

      if (!isRelevantStatus(taskStatus) || isPending()) {
        log.warn("Received irrelevant TaskStatus: " + taskStatus);
        return;
      }

      if (taskStatus.getState().equals(TaskState.TASK_RUNNING)) {
        List<TaskID> updatedPendingTasks = new ArrayList<TaskID>();
        for (TaskID pendingTaskId : pendingTasks) {
          if (!taskStatus.getTaskId().equals(pendingTaskId)) {
            updatedPendingTasks.add(pendingTaskId);
          }
        }

        setPendingTasks(updatedPendingTasks);
      } else if (isInProgress() && TaskUtils.isTerminated(taskStatus)) {
        log.info("Received terminal while InProgress TaskStatus: " + taskStatus);
        setStatus(Status.Pending);
        return;
      } else {
        log.warn("TaskStatus with no effect encountered: " + taskStatus);
      }

      if (pendingTasks.size() == 0) {
        setStatus(Status.Complete);
      }
    }
  }

  @Override
  public UUID getId() {
    return blockId;
  }

  @Override
  public String getMessage() {
    return "Broker-" + getBrokerId() + " is " + Block.getStatus(this);
  }

  @Override
  public String getName() {
    return getBrokerName();
  }

  public int getBrokerId() {
    return brokerId;
  }

  private void initializeStatus() {
    log.info("Setting initial status for: " + getName());

    TaskInfo taskInfo = getTaskInfo();

    if (taskInfo != null) {
      String configName = OfferUtils.getConfigName(taskInfo);
      log.info("TargetConfigName: " + targetConfigName + " currentConfigName: " + configName);
      if (configName.equals(targetConfigName)) {
        setStatus(Status.Complete);
      } else {
        setStatus(Status.Pending);
      }
    }

    log.info("Status initialized as " + Block.getStatus(this) + " for block: " + getName());
  }

  private synchronized TaskInfo getTaskInfo() {
    try {
      List<TaskInfo> allTasks = state.getTaskInfos();

      for (TaskInfo taskInfo : allTasks) {
        if (taskInfo.getName().equals(getBrokerName())) {
          cachedTaskInfo = taskInfo;
          return cachedTaskInfo;
        }
      }
    } catch (Exception ex) {
      log.error("Failed to retrieve TaskInfo with exception: " + ex);
    }

    return cachedTaskInfo;
  }

  private OfferRequirement getOfferRequirement() throws Exception {
    TaskInfo taskInfo = getTaskInfo();

    if (taskInfo == null) {
      return offerReqProvider.getNewOfferRequirement(targetConfigName, brokerId);
    } else {
      return offerReqProvider.getUpdateOfferRequirement(targetConfigName, taskInfo);
    }
  }

  private void setPendingTasks(OfferRequirement offerReq) {
    List<TaskID> newPendingTasks = new ArrayList<TaskID>();
    // in practice there should only be one TaskRequirement, see PersistentOfferRequirementProvider
    for (TaskRequirement taskRequirement : offerReq.getTaskRequirements()) {
      newPendingTasks.add(taskRequirement.getTaskInfo().getTaskId());
    }

    setPendingTasks(newPendingTasks);
  }

  private synchronized void setPendingTasks(List<TaskID> taskIds) {
      pendingTasks = taskIds;
  }

  public boolean isRelevantStatus(TaskStatus taskStatus) {
    if (taskStatus.getReason().equals(TaskStatus.Reason.REASON_RECONCILIATION)) {
      return false;
    }

    for (TaskID pendingTaskId : pendingTasks) {
      if (taskStatus.getTaskId().equals(pendingTaskId)) {
        return true;
      }
    }

    return false;
  }

  private boolean taskIsRunningOrStaging() {
    TaskStatus taskStatus = getTaskStatus();
    if (null == taskStatus) {
      return false;
    }
    switch (taskStatus.getState()) {
    case TASK_RUNNING:
    case TASK_STAGING:
      return true;
    default:
      return false;
    }
  }

  private TaskStatus getTaskStatus() {
    TaskInfo taskInfo = getTaskInfo();

    if (null != taskInfo) {
      try {
        return state.fetchStatus(taskInfo);
      } catch (Exception ex) {
        log.error("Failed to retrieve TaskStatus with exception: " + ex);
      }
    } else {
      return null;
    }

    return null;
  }

  public String getBrokerName() {
    return "broker-" + brokerId;
  }

  private List<TaskID> getUpdateIds() {
    List<TaskID> taskIds = new ArrayList<TaskID>();
    TaskInfo taskInfo = getTaskInfo();

    if (taskInfo != null) {
      taskIds.add(taskInfo.getTaskId());
    }

    return taskIds;
  }
}
