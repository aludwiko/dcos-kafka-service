package com.mesosphere.dcos.kafka.plan;

import com.mesosphere.dcos.kafka.offer.KafkaOfferRequirementProvider;
import com.mesosphere.dcos.kafka.offer.OfferUtils;
import com.mesosphere.dcos.kafka.scheduler.KafkaScheduler;
import com.mesosphere.dcos.kafka.state.FrameworkState;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskState;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.offer.OfferRequirement;
import org.apache.mesos.offer.TaskRequirement;
import org.apache.mesos.offer.TaskUtils;
import org.apache.mesos.scheduler.plan.DefaultStep;
import org.apache.mesos.scheduler.plan.Status;

import java.util.*;

public class KafkaUpdateStep extends DefaultStep {
  private final Log log = LogFactory.getLog(KafkaUpdateStep.class);

  private final KafkaOfferRequirementProvider offerReqProvider;
  private final String targetConfigName;
  private final FrameworkState state;
  private final int brokerId;

  private final Object pendingTaskIdsLock = new Object();
  private List<TaskID> pendingTaskIds;

  public KafkaUpdateStep(
    FrameworkState state,
    KafkaOfferRequirementProvider offerReqProvider,
    String targetConfigName,
    int brokerId) {
    super(targetConfigName, Optional.empty(), Status.PENDING, Collections.emptyList());
    this.state = state;
    this.offerReqProvider = offerReqProvider;
    this.targetConfigName = targetConfigName;
    this.brokerId = brokerId;
    TaskInfo taskInfo = fetchTaskInfo();
    pendingTaskIds = getUpdateIds(taskInfo);
    initializeStatus(taskInfo);
  }

  @Override
  public boolean isPending() {
    return getStatus() == Status.PENDING;
  }

  @Override
  public boolean isInProgress() {
    return getStatus() == Status.IN_PROGRESS;
  }

  @Override
  public boolean isComplete() {
    return getStatus() == Status.COMPLETE;
  }

  @Override
  public Optional<OfferRequirement> start() {
    log.info("Starting step: " + getName() + " with status: " + getStatus());

    if (!isPending()) {
      log.warn("Step is not pending.  start() should not be called.");
      return Optional.empty();
    }

    Optional<TaskStatus> taskStatus = fetchTaskStatus();
    if (taskIsRunningOrStaging(taskStatus)) {
      log.info("Adding task to restart list. Step: " + getName() + " Status: " + taskStatus.get());
      KafkaScheduler.restartTasks(fetchTaskInfo());
      return Optional.empty();
    }

    try {
      OfferRequirement offerReq = getOfferRequirement(fetchTaskInfo());
      List<TaskID> newPendingTasks = new ArrayList<TaskID>();
      // in practice there should only be one TaskRequirement, see PersistentOfferRequirementProvider
      for (TaskRequirement taskRequirement : offerReq.getTaskRequirements()) {
        newPendingTasks.add(taskRequirement.getTaskInfo().getTaskId());
      }
      synchronized (pendingTaskIdsLock) {
        pendingTaskIds = newPendingTasks;
      }
      return Optional.of(offerReq);
    } catch (Exception e) {
      log.error("Error getting offerRequirement: ", e);
    }

    return Optional.empty();
  }

  @Override
  public void updateOfferStatus(Collection<Protos.Offer.Operation> optionalOperations) {
    if (optionalOperations.size() > 0) {
      setStatus(Status.IN_PROGRESS);
    } else {
      setStatus(Status.PENDING);
    }
  }

  @Override
  public void restart() {
    setStatus(Status.PENDING);
  }

  @Override
  public void forceComplete() {
    try {
      KafkaScheduler.rescheduleTask(fetchTaskInfo());
    } catch (Exception ex) {
      log.error("Failed to force completion of Step: " + getId() + "with exception: ", ex);
      return;
    }
  }

  @Override
  public void update(TaskStatus taskStatus) {
    synchronized (pendingTaskIdsLock) {
      log.info(getStatus() + " Step " + getName() + " received TaskStatus. "
          + "Pending tasks: " + pendingTaskIds);

      if (isPending()) {
        log.info("Ignoring TaskStatus (Step " + getName() + " is Pending): " + taskStatus);
        return;
      }

      if (taskStatus.getReason().equals(TaskStatus.Reason.REASON_RECONCILIATION)) {
        log.info("Ignoring TaskStatus (Reason is RECONCILIATION): " + taskStatus);
        return;
      }

      if (!pendingTaskIds.contains(taskStatus.getTaskId())) {
        log.info("Ignoring TaskStatus (TaskId " + taskStatus.getTaskId().getValue() +
            " not found in pending tasks): " + taskStatus);
        return;
      }

      if (taskStatus.getState().equals(TaskState.TASK_RUNNING)) {
        pendingTaskIds.remove(taskStatus.getTaskId());
        log.info(getName() + " has updated pending tasks: " + pendingTaskIds);
      } else if (isInProgress() && TaskUtils.needsRecovery(taskStatus)) {
        log.info("Received TaskStatus indicating recovery needed while " + getName() + " is InProgress: " + taskStatus);
        setStatus(Status.PENDING);
        return;
      } else {
        log.warn("TaskStatus with no effect encountered: " + taskStatus);
      }

      if (pendingTaskIds.size() == 0) {
        setStatus(Status.COMPLETE);
      }
    }
  }

  @Override
  public String getMessage() {
    return "Broker-" + getBrokerId() + " is " + getStatus();
  }

  @Override
  public String getName() {
    return OfferUtils.brokerIdToTaskName(getBrokerId());
  }

  public int getBrokerId() {
    return brokerId;
  }

  List<TaskID> getPendingTaskIds() {
    synchronized (pendingTaskIdsLock) {
      return pendingTaskIds;
    }
  }

  private void initializeStatus(TaskInfo taskInfo) {
    log.info("Setting initial status for: " + getName());

    if (taskInfo != null) {
      String configName = OfferUtils.getConfigName(taskInfo);
      log.info("TargetConfigName: " + targetConfigName + " currentConfigName: " + configName);
      if (configName.equals(targetConfigName)) {
        setStatus(Status.COMPLETE);
      } else {
        setStatus(Status.PENDING);
      }
    }

    log.info("Status initialized as " + getStatus() + " for block: " + getName());
  }

  private OfferRequirement getOfferRequirement(TaskInfo taskInfo) throws Exception {
    if (taskInfo == null) {
      return offerReqProvider.getNewOfferRequirement(targetConfigName, getBrokerId());
    } else {
      return offerReqProvider.getUpdateOfferRequirement(targetConfigName, taskInfo);
    }
  }

  private Optional<TaskStatus> fetchTaskStatus() {
    try {
      return state.getTaskStatusForBroker(getBrokerId());
    } catch (Exception ex) {
      log.error(String.format("Failed to retrieve TaskStatus for broker %d", getBrokerId()), ex);
      return Optional.empty();
    }
  }

  private TaskInfo fetchTaskInfo() {
    try {
      Optional<TaskInfo> taskInfoOptional = state.getTaskInfoForBroker(getBrokerId());
      if (taskInfoOptional.isPresent()) {
        return taskInfoOptional.get();
      } else {
        log.warn("TaskInfo not present for broker: " + getBrokerId());
        return null;
      }
    } catch (Exception ex) {
      log.error(String.format("Failed to retrieve TaskInfo for broker %d", getBrokerId()), ex);
      return null;
    }
  }

  private static List<TaskID> getUpdateIds(TaskInfo taskInfo) {
    List<TaskID> taskIds = new ArrayList<>();

    if (taskInfo != null) {
      taskIds.add(taskInfo.getTaskId());
    }

    return taskIds;
  }

  private static boolean taskIsRunningOrStaging(Optional<TaskStatus> taskStatus) {
    if (taskStatus.isPresent()) {
      switch (taskStatus.get().getState()) {
        case TASK_RUNNING:
        case TASK_STAGING:
          return true;
        default:
          return false;
      }
    } else {
      return false;
    }
  }

}
