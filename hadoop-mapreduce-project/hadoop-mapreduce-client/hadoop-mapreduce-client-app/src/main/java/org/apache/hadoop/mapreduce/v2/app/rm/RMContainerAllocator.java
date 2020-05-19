/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapreduce.v2.app.rm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEvent;
import org.apache.hadoop.mapreduce.jobhistory.NormalizedResourceEvent;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.client.ClientService;
import org.apache.hadoop.mapreduce.v2.app.job.Job;
import org.apache.hadoop.mapreduce.v2.app.job.TaskAttempt;
import org.apache.hadoop.mapreduce.v2.app.job.Task;
import org.apache.hadoop.mapreduce.v2.app.job.event.CloneTaskAttemptContainerAssignedEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobCounterUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobDiagnosticsUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobUpdatedNodesEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.KillTaskAttemptOnUnusableEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptContainerAssignedEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptDiagnosticsUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptStatusUpdateEvent.TaskAttemptStatus;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptKillEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskCloneAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.rm.RMContainerRequestor1.ContainerRequest;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.StringInterner;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NMToken;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.Token;
import org.apache.hadoop.yarn.client.ClientRMProxy;
import org.apache.hadoop.yarn.client.api.NMTokenCache;
import org.apache.hadoop.yarn.exceptions.ApplicationAttemptNotFoundException;
import org.apache.hadoop.yarn.exceptions.ApplicationMasterNotRegisteredException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.RackResolver;
import org.apache.hadoop.yarn.util.resource.Resources;

import org.apache.hadoop.mapreduce.v2.app.speculate.LegacyTaskRuntimeEstimator;
import com.google.common.annotations.VisibleForTesting;

/**
 * Allocates the container from the ResourceManager scheduler.
 */
public class RMContainerAllocator extends RMContainerRequestor
    implements ContainerAllocator {

  static final Log LOG = LogFactory.getLog(RMContainerAllocator.class);
  
  public static final 
  float DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART = 0.05f;
  
  private static final Priority PRIORITY_FAST_FAIL_MAP;
  private static final Priority PRIORITY_REDUCE;
  private static final Priority PRIORITY_MAP;

  private List<TaskId> taskList = new ArrayList<TaskId>();
  
  @VisibleForTesting
  public static final String RAMPDOWN_DIAGNOSTIC = "Reducer preempted "
      + "to make room for pending map attempts";

  private Thread eventHandlingThread;
  private final AtomicBoolean stopped;

  static {
    PRIORITY_FAST_FAIL_MAP = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Priority.class);
    PRIORITY_FAST_FAIL_MAP.setPriority(5);
    PRIORITY_REDUCE = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Priority.class);
    PRIORITY_REDUCE.setPriority(10);
    PRIORITY_MAP = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Priority.class);
    PRIORITY_MAP.setPriority(20);
  }
  
  /*
  Vocabulary Used: 
  pending -> requests which are NOT yet sent to RM
  scheduled -> requests which are sent to RM but not yet assigned
  assigned -> requests which are assigned to a container
  completed -> request corresponding to which container has completed
  
  Lifecycle of map
  scheduled->assigned->completed
  
  Lifecycle of reduce
  pending->scheduled->assigned->completed
  
  Maps are scheduled as soon as their requests are received. Reduces are 
  added to the pending and are ramped up (added to scheduled) based 
  on completed maps and current availability in the cluster.
  */
  
  //reduces which are not yet scheduled
  private final LinkedList<ContainerRequest> pendingReduces = 
    new LinkedList<ContainerRequest>();

  //holds information about the assigned containers to task attempts
  private final AssignedRequests assignedRequests = new AssignedRequests();
  
  //holds scheduled requests to be fulfilled by RM
  private final ScheduledRequests scheduledRequests = new ScheduledRequests();
  
  private int containersAllocated = 0;
  private int containersReleased = 0;
  private int hostLocalAssigned = 0;
  private int rackLocalAssigned = 0;
  private int lastCompletedTasks = 0;
  private boolean recalculateReduceSchedule = false;
  private Resource mapResourceRequest = Resources.none();
  private Resource reduceResourceRequest = Resources.none();
  private AppContext context;
  private Configuration conf;
  private boolean reduceStarted = false;
  private float maxReduceRampupLimit = 0;
  private float maxReducePreemptionLimit = 0;
  /**
   * after this threshold, if the container request is not allocated, it is
   * considered delayed.
   */
  private long allocationDelayThresholdMs = 0;
  private float reduceSlowStart = 0;
  private long retryInterval;
  private long retrystartTime;
  private Clock clock;

  @VisibleForTesting
  protected BlockingQueue<ContainerAllocatorEvent> eventQueue
    = new LinkedBlockingQueue<ContainerAllocatorEvent>();

  private ScheduleStats scheduleStats = new ScheduleStats();
  private LegacyTaskRuntimeEstimator workload = new LegacyTaskRuntimeEstimator();

  public RMContainerAllocator(ClientService clientService, AppContext context) {
    super(clientService, context);
    this.stopped = new AtomicBoolean(false);
    this.clock = context.getClock();
    this.context = context;
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    super.serviceInit(conf);
    reduceSlowStart = conf.getFloat(
        MRJobConfig.COMPLETED_MAPS_FOR_REDUCE_SLOWSTART, 
        DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART);
    maxReduceRampupLimit = conf.getFloat(
        MRJobConfig.MR_AM_JOB_REDUCE_RAMPUP_UP_LIMIT, 
        MRJobConfig.DEFAULT_MR_AM_JOB_REDUCE_RAMP_UP_LIMIT);
    maxReducePreemptionLimit = conf.getFloat(
        MRJobConfig.MR_AM_JOB_REDUCE_PREEMPTION_LIMIT,
        MRJobConfig.DEFAULT_MR_AM_JOB_REDUCE_PREEMPTION_LIMIT);
    allocationDelayThresholdMs = conf.getInt(
        MRJobConfig.MR_JOB_REDUCER_PREEMPT_DELAY_SEC,
        MRJobConfig.DEFAULT_MR_JOB_REDUCER_PREEMPT_DELAY_SEC) * 1000;//sec -> ms
    RackResolver.init(conf);
    retryInterval = getConfig().getLong(MRJobConfig.MR_AM_TO_RM_WAIT_INTERVAL_MS,
                                MRJobConfig.DEFAULT_MR_AM_TO_RM_WAIT_INTERVAL_MS);
    // Init startTime to current time. If all goes well, it will be reset after
    // first attempt to contact RM.
    retrystartTime = System.currentTimeMillis();
    this.conf = conf;
  }

  @Override
  protected void serviceStart() throws Exception {
    this.eventHandlingThread = new Thread() {
      @SuppressWarnings("unchecked")
      @Override
      public void run() {

        ContainerAllocatorEvent event;

        while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
          try {
            event = RMContainerAllocator.this.eventQueue.take();
          } catch (InterruptedException e) {
            if (!stopped.get()) {
              LOG.error("Returning, interrupted : " + e);
            }
            return;
          }

          try {
            handleEvent(event);
          } catch (Throwable t) {
            LOG.error("Error in handling event type " + event.getType()
                + " to the ContainreAllocator", t);
            // Kill the AM
            eventHandler.handle(new JobEvent(getJob().getID(),
              JobEventType.INTERNAL_ERROR));
            return;
          }
        }
      }
    };
    this.eventHandlingThread.start();
    workload.contextualize(conf, context);
    super.serviceStart();
  }

  @Override
  protected synchronized void heartbeat() throws Exception {
    scheduleStats.updateAndLogIfChanged("Before Scheduling: ");
    List<Container> allocatedContainers = getResources();
    if (allocatedContainers != null && allocatedContainers.size() > 0) {
      scheduledRequests.assign(allocatedContainers);
    }

    int completedMaps = getJob().getCompletedMaps();
    int completedTasks = completedMaps + getJob().getCompletedReduces();
    if ((lastCompletedTasks != completedTasks) ||
          (scheduledRequests.maps.size() > 0)) {
      lastCompletedTasks = completedTasks;
      recalculateReduceSchedule = true;
    }

    if (recalculateReduceSchedule) {
      preemptReducesIfNeeded();
      scheduleReduces(
          getJob().getTotalMaps(), completedMaps,
          scheduledRequests.maps.size(), scheduledRequests.reduces.size(), 
          assignedRequests.maps.size(), assignedRequests.reduces.size(),
          mapResourceRequest, reduceResourceRequest,
          pendingReduces.size(), 
          maxReduceRampupLimit, reduceSlowStart);
      recalculateReduceSchedule = false;
    }

    scheduleStats.updateAndLogIfChanged("After Scheduling: ");
  }

  @Override
  protected void serviceStop() throws Exception {
    if (stopped.getAndSet(true)) {
      // return if already stopped
      return;
    }
    if (eventHandlingThread != null) {
      eventHandlingThread.interrupt();
    }
    super.serviceStop();
    scheduleStats.log("Final Stats: ");
  }

  @Private
  @VisibleForTesting
  AssignedRequests getAssignedRequests() {
    return assignedRequests;
  }

  @Private
  @VisibleForTesting
  ScheduledRequests getScheduledRequests() {
    return scheduledRequests;
  }

  public boolean getIsReduceStarted() {
    return reduceStarted;
  }
  
  public void setIsReduceStarted(boolean reduceStarted) {
    this.reduceStarted = reduceStarted; 
  }

  @Override
  public void handle(ContainerAllocatorEvent event) {
    int qSize = eventQueue.size();
    if (qSize != 0 && qSize % 1000 == 0) {
      LOG.info("Size of event-queue in RMContainerAllocator is " + qSize);
    }
    int remCapacity = eventQueue.remainingCapacity();
    if (remCapacity < 1000) {
      LOG.warn("Very low remaining capacity in the event-queue "
          + "of RMContainerAllocator: " + remCapacity);
    }
    try {
      eventQueue.put(event);
    } catch (InterruptedException e) {
      throw new YarnRuntimeException(e);
    }
  }

  @SuppressWarnings({ "unchecked" })
  protected synchronized void handleEvent(ContainerAllocatorEvent event) {
    recalculateReduceSchedule = true;
    if (event.getType() == ContainerAllocator.EventType.CONTAINER_REQ) {
      ContainerRequestEvent reqEvent = (ContainerRequestEvent) event;
      JobId jobId = getJob().getID();
      Resource supportedMaxContainerCapability = getMaxContainerCapability();
      if (reqEvent.getAttemptID().getTaskId().getTaskType().equals(TaskType.MAP)) {
        if (mapResourceRequest.equals(Resources.none())) {
          mapResourceRequest = reqEvent.getCapability();
          eventHandler.handle(new JobHistoryEvent(jobId,
            new NormalizedResourceEvent(
              org.apache.hadoop.mapreduce.TaskType.MAP, mapResourceRequest
                .getMemory())));
          LOG.info("mapResourceRequest:" + mapResourceRequest);
          if (mapResourceRequest.getMemory() > supportedMaxContainerCapability
            .getMemory()
              || mapResourceRequest.getVirtualCores() > supportedMaxContainerCapability
                .getVirtualCores()) {
            String diagMsg =
                "MAP capability required is more than the supported "
                    + "max container capability in the cluster. Killing the Job. mapResourceRequest: "
                    + mapResourceRequest + " maxContainerCapability:"
                    + supportedMaxContainerCapability;
            LOG.info(diagMsg);
            eventHandler.handle(new JobDiagnosticsUpdateEvent(jobId, diagMsg));
            eventHandler.handle(new JobEvent(jobId, JobEventType.JOB_KILL));
          }
        }
        // set the resources
        reqEvent.getCapability().setMemory(mapResourceRequest.getMemory());
        reqEvent.getCapability().setVirtualCores(
          mapResourceRequest.getVirtualCores());
        scheduledRequests.addMap(reqEvent);//maps are immediately scheduled
      } else {
        if (reduceResourceRequest.equals(Resources.none())) {
          reduceResourceRequest = reqEvent.getCapability();
          eventHandler.handle(new JobHistoryEvent(jobId,
            new NormalizedResourceEvent(
              org.apache.hadoop.mapreduce.TaskType.REDUCE,
              reduceResourceRequest.getMemory())));
          LOG.info("reduceResourceRequest:" + reduceResourceRequest);
          if (reduceResourceRequest.getMemory() > supportedMaxContainerCapability
            .getMemory()
              || reduceResourceRequest.getVirtualCores() > supportedMaxContainerCapability
                .getVirtualCores()) {
            String diagMsg =
                "REDUCE capability required is more than the "
                    + "supported max container capability in the cluster. Killing the "
                    + "Job. reduceResourceRequest: " + reduceResourceRequest
                    + " maxContainerCapability:"
                    + supportedMaxContainerCapability;
            LOG.info(diagMsg);
            eventHandler.handle(new JobDiagnosticsUpdateEvent(jobId, diagMsg));
            eventHandler.handle(new JobEvent(jobId, JobEventType.JOB_KILL));
          }
        }
        // set the resources
        reqEvent.getCapability().setMemory(reduceResourceRequest.getMemory());
        reqEvent.getCapability().setVirtualCores(
          reduceResourceRequest.getVirtualCores());
        TaskId tid = reqEvent.getAttemptID().getTaskId();
        long workload = computeWorkload(tid);
        ContainerRequest req= new ContainerRequest(reqEvent, PRIORITY_REDUCE);
        req.setWorkload(workload);
     	LOG.info("(pending) add reduce: " + req);
        if (reqEvent.getEarlierAttemptFailed()) {
          //add to the front of queue for fail fast
        	pendingReduces.addFirst(req);
        } else {
        	pendingReduces.add(req);
          //reduces are added to pending and are slowly ramped up
        }
      }
      
    } else if (
        event.getType() == ContainerAllocator.EventType.CONTAINER_DEALLOCATE) {
  
      LOG.info("Processing the event " + event.toString());

      TaskAttemptId aId = event.getAttemptID();
      
      boolean removed = scheduledRequests.remove(aId);
      if (!removed) {
        Set<ContainerId> containerIds = assignedRequests.get(aId);
        if (containerIds != null) {
          for(ContainerId containerId: containerIds){
        	  removed = true;
              assignedRequests.remove(aId,containerId);
              containersReleased++;
              pendingRelease.add(containerId);
              release(containerId);
          }          
        }
      }
      if (!removed) {
        LOG.error("Could not deallocate container for task attemptId " + 
            aId);
      }
    } else if (
        event.getType() == ContainerAllocator.EventType.CONTAINER_FAILED) {
      ContainerFailedEvent fEv = (ContainerFailedEvent) event;
      String host = getHost(fEv.getContMgrAddress());
      containerFailedOnHost(host);
    }
  }
  
  public long computeWorkload(TaskId id){
      	long a = workload.estimatedmaptask(id);
      	long b = workload.estimatedreducetask(id);
      	long c = workload.mapRuntimeVariance(id);
      	long d = workload.reduceRuntimeVariance(id);

      	long time = -1;;
      	
      	int mapNum = scheduledRequests.maps.size() + assignedRequests.maps.size();
      	int reduceNum = pendingReduces.size() + assignedRequests.reduces.size();
      	int wkRatio = getWorkloadRatio();
      	time = mapNum * (a + c) + reduceNum * (b + d);
      	time = time == 0 ? mapNum*wkRatio : time;
      	
      	//LOG.info("CLONE DEBUG: workload updated to be " + time 
      			//+ " MapMean="+a+" MapVar="+c+ " reduceMean="+b+" reduceVar="+d
      			//+ " Map size = " + mapNum 
      			//+ " Reduces size = " + reduceNum);
      	
      	return time;
      }
  private long computeCopiesNumer(TaskId id){
	  return this.getCloneCopiesLimit();
  }
  private int computeWeight(TaskId id){
	  return this.getWeight();
  }
private static String getHost(String contMgrAddress) {
    String host = contMgrAddress;
    String[] hostport = host.split(":");
    if (hostport.length == 2) {
      host = hostport[0];
    }
    return host;
  }

  @Private
  @VisibleForTesting
  synchronized void setReduceResourceRequest(Resource res) {
    this.reduceResourceRequest = res;
  }

  @Private
  @VisibleForTesting
  synchronized void setMapResourceRequest(Resource res) {
    this.mapResourceRequest = res;
  }

  @Private
  @VisibleForTesting
  void preemptReducesIfNeeded() {
    if (reduceResourceRequest.equals(Resources.none())) {
      return; // no reduces
    }
    //check if reduces have taken over the whole cluster and there are 
    //unassigned maps
    if (scheduledRequests.maps.size() > 0) {
      Resource resourceLimit = getResourceLimit();
      Resource availableResourceForMap =
          Resources.subtract(
            resourceLimit,
            Resources.multiply(reduceResourceRequest,
              assignedRequests.reduces.size()
                  - assignedRequests.preemptionWaitingReduces.size()));
      // availableMemForMap must be sufficient to run at least 1 map
      if (ResourceCalculatorUtils.computeAvailableContainers(availableResourceForMap,
        mapResourceRequest, getSchedulerResourceTypes()) <= 0) {
        // to make sure new containers are given to maps and not reduces
        // ramp down all scheduled reduces if any
        // (since reduces are scheduled at higher priority than maps)
        LOG.info("Ramping down all scheduled reduces:"
            + scheduledRequests.reduces.size());
        for (ContainerRequest req : scheduledRequests.reduces.values()) {
          pendingReduces.add(req);
        }
        scheduledRequests.reduces.clear();
 
        //do further checking to find the number of map requests that were
        //hanging around for a while
        int hangingMapRequests = getNumOfHangingRequests(scheduledRequests.maps);
        if (hangingMapRequests > 0) {
          // preempt for making space for at least one map
          int preemptionReduceNumForOneMap =
              ResourceCalculatorUtils.divideAndCeilContainers(mapResourceRequest,
                reduceResourceRequest, getSchedulerResourceTypes());
          int preemptionReduceNumForPreemptionLimit =
              ResourceCalculatorUtils.divideAndCeilContainers(
                Resources.multiply(resourceLimit, maxReducePreemptionLimit),
                reduceResourceRequest, getSchedulerResourceTypes());
          int preemptionReduceNumForAllMaps =
              ResourceCalculatorUtils.divideAndCeilContainers(
                Resources.multiply(mapResourceRequest, hangingMapRequests),
                reduceResourceRequest, getSchedulerResourceTypes());
          int toPreempt =
              Math.min(Math.max(preemptionReduceNumForOneMap,
                preemptionReduceNumForPreemptionLimit),
                preemptionReduceNumForAllMaps);

          LOG.info("Going to preempt " + toPreempt
              + " due to lack of space for maps");
          assignedRequests.preemptReduce(toPreempt);
        }
      }
    }
  }
 
  private int getNumOfHangingRequests(Map<TaskAttemptId, ContainerRequest> requestMap) {
    if (allocationDelayThresholdMs <= 0)
      return requestMap.size();
    int hangingRequests = 0;
    long currTime = clock.getTime();
    for (ContainerRequest request: requestMap.values()) {
      long delay = currTime - request.requestTimeMs;
      if (delay > allocationDelayThresholdMs)
        hangingRequests++;
    }
    return hangingRequests;
  }
  
  @Private
  public void scheduleReduces(
      int totalMaps, int completedMaps,
      int scheduledMaps, int scheduledReduces,
      int assignedMaps, int assignedReduces,
      Resource mapResourceReqt, Resource reduceResourceReqt,
      int numPendingReduces,
      float maxReduceRampupLimit, float reduceSlowStart) {
    
    if (numPendingReduces == 0) {
      return;
    }
    
    // get available resources for this job
    Resource headRoom = getAvailableResources();
    if (headRoom == null) {
      headRoom = Resources.none();
    }

    LOG.info("Recalculating schedule, headroom=" + headRoom);
    
    //check for slow start
    if (!getIsReduceStarted()) {//not set yet
      int completedMapsForReduceSlowstart = (int)Math.ceil(reduceSlowStart * 
                      totalMaps);
      if(completedMaps < completedMapsForReduceSlowstart) {
        LOG.info("Reduce slow start threshold not met. " +
              "completedMapsForReduceSlowstart " + 
            completedMapsForReduceSlowstart);
        return;
      } else {
        LOG.info("Reduce slow start threshold reached. Scheduling reduces.");
        setIsReduceStarted(true);
      }
    }
    
    //if all maps are assigned, then ramp up all reduces irrespective of the
    //headroom
    if (scheduledMaps == 0 && numPendingReduces > 0) {
      LOG.info("All maps assigned. " +
          "Ramping up all remaining reduces:" + numPendingReduces);
      scheduleAllReduces();
      return;
    }

    float completedMapPercent = 0f;
    if (totalMaps != 0) {//support for 0 maps
      completedMapPercent = (float)completedMaps/totalMaps;
    } else {
      completedMapPercent = 1;
    }
    
    Resource netScheduledMapResource =
        Resources.multiply(mapResourceReqt, (scheduledMaps + assignedMaps));

    Resource netScheduledReduceResource =
        Resources.multiply(reduceResourceReqt,
          (scheduledReduces + assignedReduces));

    Resource finalMapResourceLimit;
    Resource finalReduceResourceLimit;

    // ramp up the reduces based on completed map percentage
    Resource totalResourceLimit = getResourceLimit();

    Resource idealReduceResourceLimit =
        Resources.multiply(totalResourceLimit,
          Math.min(completedMapPercent, maxReduceRampupLimit));
    Resource ideaMapResourceLimit =
        Resources.subtract(totalResourceLimit, idealReduceResourceLimit);

    // check if there aren't enough maps scheduled, give the free map capacity
    // to reduce.
    // Even when container number equals, there may be unused resources in one
    // dimension
    if (ResourceCalculatorUtils.computeAvailableContainers(ideaMapResourceLimit,
      mapResourceReqt, getSchedulerResourceTypes()) >= (scheduledMaps + assignedMaps)) {
      // enough resource given to maps, given the remaining to reduces
      Resource unusedMapResourceLimit =
          Resources.subtract(ideaMapResourceLimit, netScheduledMapResource);
      finalReduceResourceLimit =
          Resources.add(idealReduceResourceLimit, unusedMapResourceLimit);
      finalMapResourceLimit =
          Resources.subtract(totalResourceLimit, finalReduceResourceLimit);
    } else {
      finalMapResourceLimit = ideaMapResourceLimit;
      finalReduceResourceLimit = idealReduceResourceLimit;
    }

    LOG.info("completedMapPercent " + completedMapPercent
        + " totalResourceLimit:" + totalResourceLimit
        + " finalMapResourceLimit:" + finalMapResourceLimit
        + " finalReduceResourceLimit:" + finalReduceResourceLimit
        + " netScheduledMapResource:" + netScheduledMapResource
        + " netScheduledReduceResource:" + netScheduledReduceResource);

    int rampUp =
        ResourceCalculatorUtils.computeAvailableContainers(Resources.subtract(
                finalReduceResourceLimit, netScheduledReduceResource),
            reduceResourceReqt, getSchedulerResourceTypes());

    if (rampUp > 0) {
      rampUp = Math.min(rampUp, numPendingReduces);
      LOG.info("Ramping up " + rampUp);
      rampUpReduces(rampUp);
    } else if (rampUp < 0) {
      int rampDown = -1 * rampUp;
      rampDown = Math.min(rampDown, scheduledReduces);
      LOG.info("Ramping down " + rampDown);
      rampDownReduces(rampDown);
    }
  }

  @Private
  public void scheduleAllReduces() {
    for (ContainerRequest req : pendingReduces) {
      scheduledRequests.addReduce(req);
    }
    pendingReduces.clear();
  }
  
  @Private
  public void rampUpReduces(int rampUp) {
    //more reduce to be scheduled
    for (int i = 0; i < rampUp; i++) {
      ContainerRequest request = pendingReduces.removeFirst();
      scheduledRequests.addReduce(request);
    }
  }
  
  @Private
  public void rampDownReduces(int rampDown) {
    //remove from the scheduled and move back to pending
    for (int i = 0; i < rampDown; i++) {
      ContainerRequest request = scheduledRequests.removeReduce();
      pendingReduces.add(request);
    }
  }
  
  @SuppressWarnings("unchecked")
  private List<Container> getResources() throws Exception {
    // will be null the first time
    Resource headRoom =
        getAvailableResources() == null ? Resources.none() :
            Resources.clone(getAvailableResources());
    AllocateResponse response;
    /*
     * If contact with RM is lost, the AM will wait MR_AM_TO_RM_WAIT_INTERVAL_MS
     * milliseconds before aborting. During this interval, AM will still try
     * to contact the RM.
     */
    try {
      LOG.info("update workload, weight, copynumber, taskList and send requests!");
      updateTaskList();
      long wl = 0;
      if (taskList.size() != 0) {
    	 wl = computeWorkload(taskList.get(0));
    	 updateWorkloadForAsks(wl);
    	 updateCopiesNumberForAsks();
    	 updateWeightForAsks();
    	 updateMapTaskListForAsks(scheduledRequests.maps);
    	 updateFailedMapTaskListForAsks(scheduledRequests.earlierFailedMaps);
    	 updateReduceTaskListForAsks(scheduledRequests.reduces);
      }
      response = makeRemoteRequest(wl);
      // Reset retry count if no exception occurred.
      retrystartTime = System.currentTimeMillis();
    } catch (ApplicationAttemptNotFoundException e ) {
      // This can happen if the RM has been restarted. If it is in that state,
      // this application must clean itself up.
      eventHandler.handle(new JobEvent(this.getJob().getID(),
        JobEventType.JOB_AM_REBOOT));
      throw new YarnRuntimeException(
        "Resource Manager doesn't recognize AttemptId: "
            + this.getContext().getApplicationID(), e);
    } catch (ApplicationMasterNotRegisteredException e) {
      LOG.info("ApplicationMaster is out of sync with ResourceManager,"
          + " hence resync and send outstanding requests.");
      // RM may have restarted, re-register with RM.
      lastResponseID = 0;
      register();
      addOutstandingRequestOnResync();
      return null;
    } catch (Exception e) {
      // This can happen when the connection to the RM has gone down. Keep
      // re-trying until the retryInterval has expired.
      if (System.currentTimeMillis() - retrystartTime >= retryInterval) {
        LOG.error("Could not contact RM after " + retryInterval + " milliseconds.");
        eventHandler.handle(new JobEvent(this.getJob().getID(),
                                         JobEventType.INTERNAL_ERROR));
        throw new YarnRuntimeException("Could not contact RM after " +
                                retryInterval + " milliseconds.");
      }
      // Throw this up to the caller, which may decide to ignore it and
      // continue to attempt to contact the RM.
      throw e;
    }
    Resource newHeadRoom =
        getAvailableResources() == null ? Resources.none()
            : getAvailableResources();
    List<Container> newContainers = response.getAllocatedContainers();
    // Setting NMTokens
    if (response.getNMTokens() != null) {
      for (NMToken nmToken : response.getNMTokens()) {
        NMTokenCache.setNMToken(nmToken.getNodeId().toString(),
            nmToken.getToken());
      }
    }

    // Setting AMRMToken
    if (response.getAMRMToken() != null) {
      updateAMRMToken(response.getAMRMToken());
    }

    List<ContainerStatus> finishedContainers = response.getCompletedContainersStatuses();
    if (newContainers.size() + finishedContainers.size() > 0
        || !headRoom.equals(newHeadRoom)) {
      //something changed
      recalculateReduceSchedule = true;
      if (LOG.isDebugEnabled() && !headRoom.equals(newHeadRoom)) {
        LOG.debug("headroom=" + newHeadRoom);
      }
    }

    if (LOG.isDebugEnabled()) {
      for (Container cont : newContainers) {
        LOG.debug("Received new Container :" + cont);
      }
    }

    //Called on each allocation. Will know about newly blacklisted/added hosts.
    computeIgnoreBlacklisting();

    handleUpdatedNodes(response);

    for (ContainerStatus cont : finishedContainers) {
      LOG.info("Received completed container " + cont.getContainerId());
      TaskAttemptId attemptID = assignedRequests.get(cont.getContainerId());
      if (attemptID == null) {
        LOG.error("Container complete event for unknown container id "
            + cont.getContainerId());
      } else {
        pendingRelease.remove(cont.getContainerId());
        assignedRequests.remove(attemptID,cont.getContainerId());
        taskList.remove(attemptID.getTaskId());
        // send the container completed event to Task attempt
        eventHandler.handle(createContainerFinishedEvent(cont, attemptID));
        
        // Send the diagnostics
        String diagnostics = StringInterner.weakIntern(cont.getDiagnostics());
        eventHandler.handle(new TaskAttemptDiagnosticsUpdateEvent(attemptID,
            diagnostics));
      }      
    }
    return newContainers;
  }

  private void updateAMRMToken(Token token) throws IOException {
    org.apache.hadoop.security.token.Token<AMRMTokenIdentifier> amrmToken =
        new org.apache.hadoop.security.token.Token<AMRMTokenIdentifier>(token
          .getIdentifier().array(), token.getPassword().array(), new Text(
          token.getKind()), new Text(token.getService()));
    UserGroupInformation currentUGI = UserGroupInformation.getCurrentUser();
    currentUGI.addToken(amrmToken);
    amrmToken.setService(ClientRMProxy.getAMRMTokenService(getConfig()));
  }

  @VisibleForTesting
  public TaskAttemptEvent createContainerFinishedEvent(ContainerStatus cont,
      TaskAttemptId attemptID) {
    if (cont.getExitStatus() == ContainerExitStatus.ABORTED
        || cont.getExitStatus() == ContainerExitStatus.PREEMPTED) {
      // killed by framework
      return new TaskAttemptEvent(attemptID,
          TaskAttemptEventType.TA_KILL);
    } else {
      return new TaskAttemptEvent(attemptID,
          TaskAttemptEventType.TA_CONTAINER_COMPLETED);
    }
  }
  
  @SuppressWarnings("unchecked")
  private void handleUpdatedNodes(AllocateResponse response) {
    // send event to the job about on updated nodes
    List<NodeReport> updatedNodes = response.getUpdatedNodes();
    if (!updatedNodes.isEmpty()) {

      // send event to the job to act upon completed tasks
      eventHandler.handle(new JobUpdatedNodesEvent(getJob().getID(),
          updatedNodes));

      // act upon running tasks
      HashSet<NodeId> unusableNodes = new HashSet<NodeId>();
      for (NodeReport nr : updatedNodes) {
        NodeState nodeState = nr.getNodeState();
        if (nodeState.isUnusable()) {
          unusableNodes.add(nr.getNodeId());
        }
      }
      for (int i = 0; i < 2; ++i) {
        HashMap<TaskAttemptId, Set<Container>> taskSet = i == 0 ? assignedRequests.maps
            : assignedRequests.reduces;
        // kill running containers
        for (Map.Entry<TaskAttemptId, Set<Container>> entry : taskSet.entrySet()) {
          TaskAttemptId tid = entry.getKey();
          for(Container container : entry.getValue()){
        	NodeId taskAttemptNodeId = container.getNodeId();
        	if (unusableNodes.contains(taskAttemptNodeId)) {
              LOG.info("Killing relevant taskAttempts of the attempt :" + tid
                  + " because it is running on unusable node:"
                  + taskAttemptNodeId);
              killAttemptOnUnusableNode(tid,container,taskAttemptNodeId);              
            }
          }          
        }
      }
    }
  }
  
  @SuppressWarnings("unchecked")
  private void killAttemptOnUnusableNode(TaskAttemptId tid, Container container, 
		  NodeId taskAttemptNodeId){
	Set<TaskAttemptId> relevantAttempts =tid.getRelevantAttemptId();
	for(TaskAttemptId relevantAttempt: relevantAttempts){
		eventHandler.handle(new KillTaskAttemptOnUnusableEvent(relevantAttempt, 
		    container.getId(), "TaskAttempt killed because it ran on unusable node"
		        + taskAttemptNodeId));
	}
  }
  
  @Private
  public Resource getResourceLimit() {
    Resource headRoom = getAvailableResources();
    if (headRoom == null) {
      headRoom = Resources.none();
    }
    Resource assignedMapResource =
        Resources.multiply(mapResourceRequest, assignedRequests.maps.size());
    Resource assignedReduceResource =
        Resources.multiply(reduceResourceRequest,
          assignedRequests.reduces.size());
    return Resources.add(headRoom,
      Resources.add(assignedMapResource, assignedReduceResource));
  }

  public void updateTaskList(){
	  Iterator<TaskId> iter = taskList.iterator();
	  while (iter.hasNext()) {
		  TaskId taskID = iter.next(); 
		  Job job = context.getJob(taskID.getJobId());
		  Task task = job.getTask(taskID);
		  Map<TaskAttemptId, TaskAttempt> attempts = task.getAttempts();
		  boolean flag = false;
		  for (TaskAttempt taskAttempt : attempts.values()) {
			  LOG.info("task " + taskID + " has attempts " + taskAttempt + " state " + taskAttempt.getState());
			  if(taskAttempt.getState() == TaskAttemptState.SUCCEEDED){
			      workload.enrollAttempt(taskAttempt.getID(), taskAttempt.getLaunchTime());
		          workload.updateAttempt(taskAttempt.getID(), taskAttempt.getFinishTime());
		          LOG.info("task " + taskID + " launched at " + taskAttempt.getLaunchTime()
				  	+ " finished at " + taskAttempt.getFinishTime());
		          flag = true;
	          }
		  }
		  if (flag) {
			  iter.remove();  
		  }
	  }
	  LOG.info("updated taskList = " + taskList);
  }
  
  @Private
  @VisibleForTesting
  class ScheduledRequests {
    
	private final LinkedList<TaskAttemptId> earlierFailedMaps = 
	  new LinkedList<TaskAttemptId>();
	private final LinkedList<TaskAttemptId> earlierFailedMapsCash = 
	  new LinkedList<TaskAttemptId>();
		    
	/** Maps from a host to a list of Map tasks with data on the host */
	private final Map<String, LinkedList<TaskAttemptId>> mapsHostMapping = 
	  new HashMap<String, LinkedList<TaskAttemptId>>();
	private final Map<String, LinkedList<TaskAttemptId>> mapsRackMapping = 
      new HashMap<String, LinkedList<TaskAttemptId>>();
	private final Map<String, LinkedList<TaskAttemptId>> mapsHostMappingCash = 
	  new HashMap<String, LinkedList<TaskAttemptId>>();
	private final Map<String, LinkedList<TaskAttemptId>> mapsRackMappingCash = 
	  new HashMap<String, LinkedList<TaskAttemptId>>();
	@VisibleForTesting
	final Map<TaskAttemptId, ContainerRequest> maps =
	  new LinkedHashMap<TaskAttemptId, ContainerRequest>();
	final Map<TaskAttemptId, ContainerRequest> mapsCash =
	  new LinkedHashMap<TaskAttemptId, ContainerRequest>();
	final Map<TaskAttemptId, ContainerRequest> mapsCashBackUp =
	  new LinkedHashMap<TaskAttemptId, ContainerRequest>();
		    
	private final LinkedHashMap<TaskAttemptId, ContainerRequest> reduces = 
      new LinkedHashMap<TaskAttemptId, ContainerRequest>();
	private final LinkedHashMap<TaskAttemptId, ContainerRequest> reducesCash = 
	  new LinkedHashMap<TaskAttemptId, ContainerRequest>();
	private final LinkedHashMap<TaskAttemptId, ContainerRequest> reducesCashBackUp = 
	  new LinkedHashMap<TaskAttemptId, ContainerRequest>();
	
	private final LinkedHashMap<TaskAttemptId, Integer> cloneCopiesCounter = 
	  new LinkedHashMap<TaskAttemptId, Integer>();
	

    boolean remove(TaskAttemptId tId) {
      ContainerRequest req = null;
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
        req = maps.remove(tId);
      } else {
        req = reduces.remove(tId);
      }
      
      if (req == null) {
        return false;
      } else {
        decContainerReq(req);
        return true;
      }
    }
    
    ContainerRequest removeReduce() {
      Iterator<Entry<TaskAttemptId, ContainerRequest>> it = reduces.entrySet().iterator();
      if (it.hasNext()) {
        Entry<TaskAttemptId, ContainerRequest> entry = it.next();
        it.remove();
        decContainerReq(entry.getValue());
        return entry.getValue();
      }
      return null;
    }
    
    
    void addMap(ContainerRequestEvent event) {
      //LOG.info("CLONE Debug: map task added!");
      ContainerRequest request = null;
      TaskId tid = event.getAttemptID().getTaskId();
      taskList.add(tid);
      
      LOG.info("task " + tid + " statistics: " + workload.dataStatisticsForTask(tid));
      long workload = computeWorkload(tid);
      long copiesNumber = computeCopiesNumer(tid);
      int weight = computeWeight(tid);
      
      if (event.getEarlierAttemptFailed()) {
        earlierFailedMaps.add(event.getAttemptID());
        request = new ContainerRequest(event, PRIORITY_FAST_FAIL_MAP);
        request.setWorkload(workload);
        request.setCopiesNumber(copiesNumber);
        request.setWeight(weight);
        LOG.info("Added "+event.getAttemptID()+" to list of failed maps");
      } else {
        for (String host : event.getHosts()) {
          LinkedList<TaskAttemptId> list = mapsHostMapping.get(host);
          if (list == null) {
            list = new LinkedList<TaskAttemptId>();
          }
          list.add(event.getAttemptID());
          mapsHostMapping.put(host, list);
          if (LOG.isDebugEnabled()) {
            LOG.debug("Added attempt req to host " + host);
          }
       }
       for (String rack: event.getRacks()) {
         LinkedList<TaskAttemptId> list = mapsRackMapping.get(rack);
         if (list == null) {
           list = new LinkedList<TaskAttemptId>();
         }
         list.add(event.getAttemptID());
         mapsRackMapping.put(rack, list);
         if (LOG.isDebugEnabled()) {
            LOG.debug("Added attempt req to rack " + rack);
         }
       }
       request = new ContainerRequest(event, PRIORITY_MAP);
       request.setWorkload(workload);
       request.setCopiesNumber(copiesNumber);
       request.setWeight(weight);
      }
      maps.put(event.getAttemptID(), request);
      LOG.info("add map: " + request + "to the attempt" + event.getAttemptID());
      addContainerReq(request);
    }
    
    
    void addReduce(ContainerRequest req) {
      reduces.put(req.attemptID, req);
      LOG.info("schedule reduce: " + req);
      taskList.add(req.attemptID.getTaskId());
      addContainerReq(req);
    }
    
    // this method will change the list of allocatedContainers.
    private void assign(List<Container> allocatedContainers) {
      Iterator<Container> it = allocatedContainers.iterator();
      LOG.info("Got allocated containers " + allocatedContainers.size());
      containersAllocated += allocatedContainers.size();
      while (it.hasNext()) {
        Container allocated = it.next();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Assigning container " + allocated.getId()
              + " with priority " + allocated.getPriority() + " to NM "
              + allocated.getNodeId());
        }
        
        // check if allocated container meets memory requirements 
        // and whether we have any scheduled tasks that need 
        // a container to be assigned
        boolean isAssignable = true;
        Priority priority = allocated.getPriority();
        Resource allocatedResource = allocated.getResource();
        if (PRIORITY_FAST_FAIL_MAP.equals(priority) 
            || PRIORITY_MAP.equals(priority)) {
          if (ResourceCalculatorUtils.computeAvailableContainers(allocatedResource,
              mapResourceRequest, getSchedulerResourceTypes()) <= 0
              || maps.isEmpty()) {
            LOG.info("Cannot assign container " + allocated 
                + " for a map as either "
                + " container memory less than required " + mapResourceRequest
                + " or no pending map tasks - maps.isEmpty=" 
                + maps.isEmpty()); 
            if(ResourceCalculatorUtils.computeAvailableContainers(allocatedResource,
                    mapResourceRequest, getSchedulerResourceTypes()) > 0){
            	LOG.info("Cannot assign container " + allocated +" only because of maps."
            			+ "isEmpty="+maps.isEmpty() + " the allocated container's request"
            			+ "is from attempt" + allocated.getId().getApplicationAttemptId().toString());
            }
            isAssignable = false; 
          }
        } 
        else if (PRIORITY_REDUCE.equals(priority)) {
          if (ResourceCalculatorUtils.computeAvailableContainers(allocatedResource,
              reduceResourceRequest, getSchedulerResourceTypes()) <= 0
              || reduces.isEmpty()) {
            LOG.info("Cannot assign container " + allocated 
                + " for a reduce as either "
                + " container memory less than required " + reduceResourceRequest
                + " or no pending reduce tasks - reduces.isEmpty=" 
                + reduces.isEmpty()); 
            isAssignable = false;
          }
        } else {
          LOG.warn("Container allocated at unwanted priority: " + priority + 
              ". Returning to RM...");
          isAssignable = false;
        }
        
        if(!isAssignable) {
          // release container if we could not assign it 
          containerNotAssigned(allocated);
          it.remove();
          continue;
        }
        
        // do not assign if allocated container is on a  
        // blacklisted host
        String allocatedHost = allocated.getNodeId().getHost();
        if (isNodeBlacklisted(allocatedHost)) {
          // we need to request for a new container 
          // and release the current one
          LOG.info("Got allocated container on a blacklisted "
              + " host "+allocatedHost
              +". Releasing container " + allocated);

          // find the request matching this allocated container 
          // and replace it with a new one 
          ContainerRequest toBeReplacedReq = 
              getContainerReqToReplace(allocated);
          if (toBeReplacedReq != null) {
            LOG.info("Placing a new container request for task attempt " 
                + toBeReplacedReq.attemptID);
            ContainerRequest newReq = 
                getFilteredContainerRequest(toBeReplacedReq);
            decContainerReq(toBeReplacedReq);
            if (toBeReplacedReq.attemptID.getTaskId().getTaskType() ==
                TaskType.MAP) {
              maps.put(newReq.attemptID, newReq);
            }
            else {
              reduces.put(newReq.attemptID, newReq);
            }
            addContainerReq(newReq);
          }
          else {
            LOG.info("Could not map allocated container to a valid request."
                + " Releasing allocated container " + allocated);
          }
          
          // release container if we could not assign it 
          containerNotAssigned(allocated);
          it.remove();
          continue;
        }
      }
      
      //clone all the fields to the cash.
      for (Map.Entry<TaskAttemptId, ContainerRequest> entry : maps.entrySet()){
          if(entry.getValue().getCopiesNumber()>0){
        	  this.mapsCash.put(entry.getKey(),entry.getValue());
          }
      }
      for (Map.Entry<TaskAttemptId, ContainerRequest> entry : reduces.entrySet()){
          if(entry.getValue().getCopiesNumber()>0){
        	  this.reducesCash.put(entry.getKey(),entry.getValue());
          }
      }
      this.mapsCashBackUp.putAll(mapsCash);
      this.reducesCashBackUp.putAll(reducesCash);
      this.earlierFailedMapsCash.addAll(earlierFailedMaps);
      this.mapsHostMappingCash.putAll(mapsHostMapping);
      this.mapsRackMappingCash.putAll(mapsRackMapping);
      
      LOG.info("CLONE DEDUG: "
    		+ "mapsCash.size = " + mapsCash.size()
    		+ "reducesCash.size = " + reducesCash.size()
      		+ "earlierFailedMapsCash.size = " + earlierFailedMapsCash.size()
      		+ "mapsHostMappingCash.size = " + mapsHostMappingCash.size()
      		+ "mapsRackMappingCash.size = " + mapsRackMappingCash.size());

      assignContainers(allocatedContainers);
      
      // assign containers for the task clone
      it = allocatedContainers.iterator();
      if (it.hasNext()&&(!mapsCash.isEmpty()||!reducesCash.isEmpty())) {
    	LOG.info("Got allocated containers " + allocatedContainers.size()
    		+ "for clone");
        LOG.info("Assign unassigned container for the task clone");
        assignCloneContainers(allocatedContainers);
      }
      
      //release not assined container
      it = allocatedContainers.iterator();
      while (it.hasNext()) {
        Container allocated = it.next();
        LOG.info("Releasing unassigned and invalid container " 
            + allocated + ". RM may have assignment issues");
        containerNotAssigned(allocated);
      }
    }
    
    
    @SuppressWarnings("unchecked")
    private void containerAssigned(Container allocated, 
                                    ContainerRequest assigned) {
      // Update resource requests
      decContainerReq(assigned);

      // send the container-assigned event to task attempt
      eventHandler.handle(new TaskAttemptContainerAssignedEvent(
          assigned.attemptID, allocated, applicationACLs));

      assignedRequests.add(allocated, assigned.attemptID);

      if (LOG.isDebugEnabled()) {
        LOG.info("Assigned container (" + allocated + ") "
            + " to task " + assigned.attemptID + " on node "
            + allocated.getNodeId().toString());
      }
    }
    
    /***
     * Assign the clone container to one <b>'new'</b> task attempt, we need to
     * notice the task implementation to create one new task attempt implementation
     * first, which was communicated by event handler with Event <b>'TaskCloneAttemptEvent'</b>.</br>
     * And then assign the 'allocated' container to that 'new' created task attempt
     * @param allocated is the container allocated by RM
     * @param assigned is the container requested from task attempt (<b>original</b> task attempt)
     */
    @SuppressWarnings("unchecked")
	private void cloneContainerAssigned(Container allocated, 
            ContainerRequest assigned) {      
      // send the container-assigned event to task attempt
    	
      eventHandler.handle(new TaskCloneAttemptEvent(assigned.attemptID, allocated, applicationACLs, assigned.copiesNumber));      
      assignedRequests.add(allocated, assigned.attemptID);
      if (LOG.isInfoEnabled()) {
    	  LOG.info("Assigned clone container (" + allocated + ") "
    		  + " to task " + assigned.attemptID + " on node "
    		  + allocated.getNodeId().toString());
      }
    }
    
    private void containerNotAssigned(Container allocated) {
      containersReleased++;
      pendingRelease.add(allocated.getId());
      release(allocated.getId());      
    }
    
    /***
     * The less the effective workload of request is, the better for it
     * to do the clone
     * @param 
     * @return Failed map task container return NULL</br>
     * Reduce task container return the best worth container request
     */
    private ContainerRequest getBestWorthRequestAssignWithoutLocality(Container allocated) {
    	ContainerRequest assigned = null;
    	Priority priority = allocated.getPriority();
        if (PRIORITY_FAST_FAIL_MAP.equals(priority)) {
          //no need to assign clone task for fail map task. 
          //TODO: this need to update in the RM as well
          LOG.info("RM assign container " + allocated + " to fast fail map"
          		+ "but we would not clone the fail map task");
          assigned = assignToCloneFailedMap(allocated);
        } else if (PRIORITY_REDUCE.equals(priority)) {
          LOG.info("Assigning clone container " + allocated + " to reduce");
          assigned = assignToCloneReduce(allocated);
        }    	
    	return assigned;
    }
    
    private ContainerRequest assignWithoutLocality(Container allocated) {
      ContainerRequest assigned = null;
      
      Priority priority = allocated.getPriority();
      if (PRIORITY_FAST_FAIL_MAP.equals(priority)) {
        LOG.info("Assigning container " + allocated + " to fast fail map");
        assigned = assignToFailedMap(allocated);
      } else if (PRIORITY_REDUCE.equals(priority)) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Assigning container " + allocated + " to reduce");
        }
        assigned = assignToReduce(allocated);
      }
        
      return assigned;
    }
        
    private void assignContainers(List<Container> allocatedContainers) {
      Iterator<Container> it = allocatedContainers.iterator();
      while (it.hasNext()) {
        Container allocated = it.next();
        ContainerRequest assigned = assignWithoutLocality(allocated);
        if (assigned != null) {
          containerAssigned(allocated, assigned);
          it.remove();
        }
      }

      assignMapsWithLocality(allocatedContainers);
    }
    
    //find the best worth request with different priority and assign the container
    private void assignCloneContainers(List<Container> allocatedContainers) {
    	Iterator<Container> it = allocatedContainers.iterator();
    	ContainerRequest assigned = null;
        while (it.hasNext()) {
          Container allocated = it.next();
          assigned = getBestWorthRequestAssignWithoutLocality(allocated);
          if (assigned != null) {
        	cloneContainerAssigned(allocated, assigned);
            it.remove();
          }
        }
        if(LOG.isInfoEnabled()){
        	LOG.info("Have finish cloning reduce task, now we have ["
        			+allocatedContainers.size()+"] more contianers need"
        			+ "to assign.");
        }
        assignCloneMapsWithLocality(allocatedContainers);
      }
    
    private ContainerRequest getContainerReqToReplace(Container allocated) {
      LOG.info("Finding containerReq for allocated container: " + allocated);
      Priority priority = allocated.getPriority();
      ContainerRequest toBeReplaced = null;
      if (PRIORITY_FAST_FAIL_MAP.equals(priority)) {
        LOG.info("Replacing FAST_FAIL_MAP container " + allocated.getId());
        Iterator<TaskAttemptId> iter = earlierFailedMaps.iterator();
        while (toBeReplaced == null && iter.hasNext()) {
          toBeReplaced = maps.get(iter.next());
        }
        LOG.info("Found replacement: " + toBeReplaced);
        return toBeReplaced;
      }
      else if (PRIORITY_MAP.equals(priority)) {
        LOG.info("Replacing MAP container " + allocated.getId());
        // allocated container was for a map
        String host = allocated.getNodeId().getHost();
        LinkedList<TaskAttemptId> list = mapsHostMapping.get(host);
        if (list != null && list.size() > 0) {
          TaskAttemptId tId = list.removeLast();
          if (maps.containsKey(tId)) {
            toBeReplaced = maps.remove(tId);
          }
        }
        else {
          TaskAttemptId tId = maps.keySet().iterator().next();
          toBeReplaced = maps.remove(tId);          
        }        
      }
      else if (PRIORITY_REDUCE.equals(priority)) {
        TaskAttemptId tId = reduces.keySet().iterator().next();
        toBeReplaced = reduces.remove(tId);    
      }
      LOG.info("Found replacement: " + toBeReplaced);
      return toBeReplaced;
    }
    
    /***
     * try to assign to reduces if we could find the best worth clone failed map
     * the problem is that, if we clone the failed map task, as long as one of 
     * the copies failed, we need to to launch other 3 copies if we have enough 
     * memory, as the failed map task has the highest priority. the memory used 
     * increased exponentially, it isvery dangerous and inefficient.
     */
    private ContainerRequest assignToCloneFailedMap(Container allocated) {
      ContainerRequest assigned = null;
      return assigned;
    }
    
    @SuppressWarnings("unchecked")
    private ContainerRequest assignToFailedMap(Container allocated) {
      //try to assign to earlierFailedMaps if present
      ContainerRequest assigned = null;
      while (assigned == null && earlierFailedMaps.size() > 0) {
        TaskAttemptId tId = earlierFailedMaps.removeFirst();      
        if (maps.containsKey(tId)) {
          assigned = maps.remove(tId);
          JobCounterUpdateEvent jce =
            new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
          jce.addCounterUpdate(JobCounter.OTHER_LOCAL_MAPS, 1);
          eventHandler.handle(jce);
          LOG.info("Assigned from earlierFailedMaps");
          break;
        }
      }
      return assigned;
    }
    
    private ContainerRequest assignToCloneReduce(Container allocated) {
      ContainerRequest assigned = null;
      
      TaskAttemptId bestTaskAttemptId = null;
      ContainerRequest bestContainerRequest = null;
      long bestWorkload = Long.MAX_VALUE;
      //try to find the most completed reduce task and assign clone of it.
      
      if(reducesCash.isEmpty()){
    	  reducesCash.putAll(mapsCashBackUp);
      }
      
      if (assigned == null && reducesCash.size() > 0) {
    	for (Map.Entry<TaskAttemptId, ContainerRequest> requestEntry : reducesCash.entrySet()) {
    	  long estimatedEffictiveWorkload = requestEntry.getValue().workload;
          if (estimatedEffictiveWorkload < bestWorkload) {
        	  bestTaskAttemptId = requestEntry.getKey();
        	  bestContainerRequest = requestEntry.getValue();
        	  bestWorkload = estimatedEffictiveWorkload;
    	  }
    	}
      }
            
      LOG.info("Assigned clone container to reduce attempt " + 
      bestTaskAttemptId.toString() + "'s container" + 
      bestContainerRequest.toString());
      
      assigned = bestContainerRequest;
      
      reducesCash.remove(bestTaskAttemptId);
      
      if(bestTaskAttemptId!=null && bestContainerRequest !=null){  
    	  Integer count = cloneCopiesCounter.containsKey(bestTaskAttemptId) ? 
            	  cloneCopiesCounter.get(bestTaskAttemptId) : 0;
          cloneCopiesCounter.put(bestTaskAttemptId, count + 1);
          if(count >= bestContainerRequest.copiesNumber-1){
              reducesCashBackUp.remove(bestTaskAttemptId);
          }
      }
      
      return assigned;
    }
    
    private ContainerRequest assignToReduce(Container allocated) {
      ContainerRequest assigned = null;
      //try to assign to reduces if present
      if (assigned == null && reduces.size() > 0) {
        TaskAttemptId tId = reduces.keySet().iterator().next();
        assigned = reduces.remove(tId);
        LOG.info("Assigned to reduce");
      }
      return assigned;
    }
    	
    /****** 
     * The most important condition is Locality of the container, based on the
     * locality, then choose the least workload task attempt.</br>
     * The idea is like this: after all normal assignment has been done, 
     * the remaining available container would be assigned to the task attempt 
     * which meet the locality first to do the clone (in this step, not necessary 
     * to clone 3 copies but could not more than 3), and then find the task attempt
     * which meet the least workload to do the clone without locality (in this step, 
     * max 3 copies).
     ******/
    
    @SuppressWarnings("unchecked")
    private void assignCloneMapsWithLocality(List<Container> allocatedContainers) {
    	
      LOG.info("there are " + allocatedContainers.size() +" containers "
    			+ "trying to be assigned with HOST locality");
      // try to assign to all nodes first to match node local
      Iterator<Container> it1 = allocatedContainers.iterator();
      while(it1.hasNext() && mapsCash.size() > 0){
        Container allocated = it1.next();        
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        // "if (maps.containsKey(tId))" below should be almost always true.
        // hence this while loop would almost always have O(1) complexity
        String host = allocated.getNodeId().getHost();
        String rack = RackResolver.resolve(host).getNetworkLocation();
        LOG.info("CLONE DEBUG: This clone container's host is [" + host + "]");
        LinkedList<TaskAttemptId> list = mapsHostMappingCash.get(host);
        if(list == null){
        	continue;
        }
        LOG.info("CLONE DEBUG: Based on this " + host 
        		+ " there are " + list.size() + "attempt has map request on this node");
        TaskAttemptId bestTaskAttemptId = null;
        ContainerRequest bestContainerRequest = null;
        long bestWorkload = Long.MAX_VALUE;
        
        Iterator<TaskAttemptId> tIt = list.iterator();
        
        while(tIt.hasNext()) {
          TaskAttemptId tId = tIt.next();
          //get the task attempt id which could use this allocated container
          if(mapsCash.containsKey(tId)){
        	ContainerRequest assigned = mapsCash.get(tId);
        	long estimatedEffictiveWorkload = assigned.workload;
        	if(estimatedEffictiveWorkload < bestWorkload){
              bestTaskAttemptId = tId;
              bestContainerRequest = assigned;
        	}
          }
        }
        //if there is any suitable task attempt and container request, assign
        //the container and remove this entry from mapsCash if it is the 3rd
        //copies.
        if(bestTaskAttemptId != null && bestContainerRequest != null){        	
          //assign the best worth map task clone
          cloneContainerAssigned(allocated, bestContainerRequest);
          mapsCash.remove(bestTaskAttemptId);
          it1.remove();
          //increase the counter
          Integer count = cloneCopiesCounter.containsKey(bestTaskAttemptId) ? 
        	  cloneCopiesCounter.get(bestTaskAttemptId) : 1;
          cloneCopiesCounter.put(bestTaskAttemptId, count + 1);
          //update Job counter
          JobCounterUpdateEvent jce =
            new JobCounterUpdateEvent(bestContainerRequest.attemptID.getTaskId().getJobId());
          jce.addCounterUpdate(JobCounter.DATA_LOCAL_MAPS, 1);
          eventHandler.handle(jce);
          hostLocalAssigned++;
          if (LOG.isInfoEnabled()) {
            LOG.info("Assigned based on host match " + host);
          }
          LOG.info("CLONE DEBUG: EASY TO CAPTURE: "
          		+ "This CLONE container's id is ["+ allocated.getId().toString() +"], "
          		+ "host is [" + host + "], rack is [" + rack + "], "
          		+ "this is a HOST level allocation, "
          		+ "whose job is ["+bestContainerRequest.attemptID.getTaskId().getJobId()+"], "
          		+ "whose task attemp is ["+bestContainerRequest.attemptID.toString()+"]， "
          		+ "this is the "+count+" piece of the clone");
          //if hit the limit of clone, remove this container request
          if(count >= bestContainerRequest.copiesNumber){  //count + 1 >= 3, has assigned 3 copies including this time
        	list.remove(bestTaskAttemptId);
        	mapsCashBackUp.remove(bestTaskAttemptId);
        	if(LOG.isInfoEnabled()){
        		LOG.info("CLONE DEBUG: Reach the limit amount of the tasks");
        	}
          }
          if(mapsCash.isEmpty()){
            	mapsCash.putAll(mapsCashBackUp);
          }
        }else{
        	if(LOG.isInfoEnabled()){
        		LOG.info("Cannot find one HOST locality request for this clone container"
        				+ "try to find RACK locality requests");
        	}
        }
        //if no best worth container request, continue, leave the container to the request
        //without locality
      }
      
      LOG.info("there are " + allocatedContainers.size() +" containers "
  			+ "trying to be assigned with RACK locality");
      // try to match all rack local
      Iterator<Container> it2 = allocatedContainers.iterator();
      while(it2.hasNext() && mapsCash.size() > 0){
        Container allocated = it2.next();
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        // "if (maps.containsKey(tId))" below should be almost always true.
        // hence this while loop would almost always have O(1) complexity
        String host = allocated.getNodeId().getHost();
        String rack = RackResolver.resolve(host).getNetworkLocation();
        LOG.info("CLONE DEBUG: This clone container's rack is [" + rack + "]");
        LinkedList<TaskAttemptId> list = mapsRackMappingCash.get(rack);
        if(list == null){
        	continue;
        }
        LOG.info("CLONE DEBUG: Based on this " + rack 
        		+ " there are " + list.size() + "attempt has map request on this rack");

        TaskAttemptId bestTaskAttemptId = null;
        ContainerRequest bestContainerRequest = null;
        long bestWorkload = Long.MAX_VALUE;
        
        Iterator<TaskAttemptId> tIt = list.iterator();
        
        while(tIt.hasNext()) {
          TaskAttemptId tId = tIt.next();
          if(mapsCash.containsKey(tId)){
        	ContainerRequest assigned = mapsCash.get(tId);
        	long estimatedEffictiveWorkload = assigned.workload;
        	if(estimatedEffictiveWorkload < bestWorkload){
              bestTaskAttemptId = tId;
              bestContainerRequest = assigned;
        	}
          }
        }
        if(bestTaskAttemptId != null && bestContainerRequest != null){        	
          cloneContainerAssigned(allocated, bestContainerRequest);
          it2.remove();
          mapsCash.remove(bestTaskAttemptId);
          Integer count = cloneCopiesCounter.containsKey(bestTaskAttemptId) ? 
        	  cloneCopiesCounter.get(bestTaskAttemptId) : 1;
          cloneCopiesCounter.put(bestTaskAttemptId, count + 1);
          JobCounterUpdateEvent jce =
            new JobCounterUpdateEvent(bestContainerRequest.attemptID.getTaskId().getJobId());
          jce.addCounterUpdate(JobCounter.RACK_LOCAL_MAPS, 1);
          eventHandler.handle(jce);
          rackLocalAssigned++;
          if (LOG.isInfoEnabled()) {
            LOG.info("Assigned based on rack match " + rack);
          }
          LOG.info("CLONE DEBUG: EASY TO CAPTURE: "
          		+ "This CLONE container's id is ["+ allocated.getId().toString() +"], "
          		+ "host is [" + host + "], rack is [" + rack + "], "
          		+ "this is a RACK level allocation, "
          		+ "whose job is ["+bestContainerRequest.attemptID.getTaskId().getJobId()+"], "
          		+ "whose task attemp is ["+bestContainerRequest.attemptID.toString()+"], "
          		+ "this is the "+count+" piece of the clone");
          if(count >= bestContainerRequest.copiesNumber){
        	list.remove(bestTaskAttemptId);
        	mapsCashBackUp.remove(bestTaskAttemptId);
        	if(LOG.isInfoEnabled()){
        		LOG.info("CLONE DEBUG: Reach the limit amount of the tasks");
        	}
          }
          if(mapsCash.isEmpty()){
          	mapsCash.putAll(mapsCashBackUp);
          }
        }else{
        	if(LOG.isInfoEnabled()){
        		LOG.info("Cannot find one RACK locality request for this clone container"
        				+ "try to find * locality requests");
        	}
        }
      }
      
      LOG.info("there are " + allocatedContainers.size() +" containers "
  			+ "trying to be assigned with * locality");
      //assign remaining
      Iterator<Container> it3 = allocatedContainers.iterator();
      while(it3.hasNext() && mapsCash.size() > 0){
        Container allocated = it3.next();        
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        
        String host = allocated.getNodeId().getHost();
        String rack = RackResolver.resolve(host).getNetworkLocation();
        TaskAttemptId bestTaskAttemptId = null;
        ContainerRequest bestContainerRequest = null;
        long bestWorkload = Long.MAX_VALUE;
        
        if (mapsCash.size() > 0) {
          for (Map.Entry<TaskAttemptId, ContainerRequest> mapEntry : mapsCash.entrySet()) {
        	long estimatedEffictiveWorkload = mapEntry.getValue().workload;
            if (estimatedEffictiveWorkload < bestWorkload) {
            	bestTaskAttemptId = mapEntry.getKey();
            	bestContainerRequest = mapEntry.getValue();
            	bestWorkload = estimatedEffictiveWorkload;
        	}
          }
        }
        if(bestTaskAttemptId != null && bestContainerRequest != null){        	
          cloneContainerAssigned(allocated, bestContainerRequest);
          it3.remove();
          mapsCash.remove(bestTaskAttemptId);
          if (LOG.isInfoEnabled()) {
            LOG.info("remove " + bestTaskAttemptId.toString() + " from mapsCash, mapsCash size is ["+mapsCash.size()+"] now");
          }
          Integer count = cloneCopiesCounter.containsKey(bestTaskAttemptId) ? 
          	cloneCopiesCounter.get(bestTaskAttemptId) : 1;
          cloneCopiesCounter.put(bestTaskAttemptId, count + 1);
          JobCounterUpdateEvent jce =
            new JobCounterUpdateEvent(bestContainerRequest.attemptID.getTaskId().getJobId());
          jce.addCounterUpdate(JobCounter.OTHER_LOCAL_MAPS, 1);
          eventHandler.handle(jce);
          if (LOG.isInfoEnabled()) {
            LOG.info("Assigned based on * match");
          }
          LOG.info("CLONE DEBUG: EASY TO CAPTURE: "
          		+ "This CLONE container's id is ["+ allocated.getId().toString() +"], "
          		+ "host is [" + host + "], rack is [" + rack + "], "
          		+ "this is a ANY level allocation, "
          		+ "whose job is ["+bestContainerRequest.attemptID.getTaskId().getJobId()+"], "
          		+ "whose task attemp is ["+bestContainerRequest.attemptID.toString()+"]， "
          		+ "this is the "+count+" piece of the clone");
          if(count >= bestContainerRequest.copiesNumber){
        	  mapsCashBackUp.remove(bestTaskAttemptId);
          	  if(LOG.isInfoEnabled()){
          		  LOG.info("CLONE DEBUG: Reach the limit amount of the tasks");
          	  }
          }
          if(mapsCash.size() == 0){
            	if (LOG.isInfoEnabled()) {
                    LOG.info("Load mapsCashBackup to mapsCash, which size is "+mapsCashBackUp.size());
                }
            	mapsCash.putAll(mapsCashBackUp);
          }
        }else{
        	if(LOG.isInfoEnabled()){
        		LOG.info("Cannot find one * locality request for this clone container"
        				+ ". In principle, there should be no container map requests in mapsCash"
        				+ " now. But mapsCash.size is " + mapsCash.size());
        	}
        }
      }
    }
    
    @SuppressWarnings("unchecked")
    private void assignMapsWithLocality(List<Container> allocatedContainers) {
      // try to assign to all nodes first to match node local
      Iterator<Container> it = allocatedContainers.iterator();
      while(it.hasNext() && maps.size() > 0){
        Container allocated = it.next();        
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        // "if (maps.containsKey(tId))" below should be almost always true.
        // hence this while loop would almost always have O(1) complexity
        String host = allocated.getNodeId().getHost();
        String rack = RackResolver.resolve(host).getNetworkLocation();
        LOG.info("CLONE DEBUG: This orininal container's host is [" + host + "]");
        LinkedList<TaskAttemptId> list = mapsHostMapping.get(host);
        while (list != null && list.size() > 0) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Host matched to the request list " + host);
          }
          TaskAttemptId tId = list.removeFirst();
          if (maps.containsKey(tId)) {
            ContainerRequest assigned = maps.remove(tId);
            containerAssigned(allocated, assigned);
            it.remove();
            JobCounterUpdateEvent jce =
              new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
            jce.addCounterUpdate(JobCounter.DATA_LOCAL_MAPS, 1);
            eventHandler.handle(jce);
            hostLocalAssigned++;
            if (LOG.isDebugEnabled()) {
              LOG.debug("Assigned based on host match " + host);
            }
            LOG.info("CLONE DEBUG: EASY TO CAPTURE: "
            		+ "This ORIGINAL container's id is ["+ allocated.getId().toString() +"], "
            		+ "host is [" + host + "], rack is [" + rack + "], "
            		+ "this is a HOST level allocation, "
            		+ "whose job is ["+assigned.attemptID.getTaskId().getJobId()+"], "
            		+ "whose task attemp is ["+assigned.attemptID.toString()+"]");
            break;
          }
        }
      }
      
      // try to match all rack local
      it = allocatedContainers.iterator();
      while(it.hasNext() && maps.size() > 0){
        Container allocated = it.next();
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        // "if (maps.containsKey(tId))" below should be almost always true.
        // hence this while loop would almost always have O(1) complexity
        String host = allocated.getNodeId().getHost();
        String rack = RackResolver.resolve(host).getNetworkLocation();
        LOG.info("CLONE DEBUG: This original container's rack is [" + rack + "]");
        LinkedList<TaskAttemptId> list = mapsRackMapping.get(rack);
        while (list != null && list.size() > 0) {
          TaskAttemptId tId = list.removeFirst();
          if (maps.containsKey(tId)) {
            ContainerRequest assigned = maps.remove(tId);
            containerAssigned(allocated, assigned);
            it.remove();
            JobCounterUpdateEvent jce =
              new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
            jce.addCounterUpdate(JobCounter.RACK_LOCAL_MAPS, 1);
            eventHandler.handle(jce);
            rackLocalAssigned++;
            if (LOG.isDebugEnabled()) {
              LOG.debug("Assigned based on rack match " + rack);
            }
            LOG.info("CLONE DEBUG: EASY TO CAPTURE: "
            		+ "This ORIGINAL container's id is ["+ allocated.getId().toString() +"], "
            		+ "host is [" + host + "], rack is [" + rack + "], "
            		+ "this is a RACK level allocation, "
            		+ "whose job is ["+assigned.attemptID.getTaskId().getJobId()+"], "
            		+ "whose task attemp is ["+assigned.attemptID.toString()+"]");
            break;
          }
        }
      }
      
      // assign remaining
      it = allocatedContainers.iterator();
      while(it.hasNext() && maps.size() > 0){
        Container allocated = it.next();
        Priority priority = allocated.getPriority();
        assert PRIORITY_MAP.equals(priority);
        String host = allocated.getNodeId().getHost();
        String rack = RackResolver.resolve(host).getNetworkLocation();
        TaskAttemptId tId = maps.keySet().iterator().next();
        ContainerRequest assigned = maps.remove(tId);
        containerAssigned(allocated, assigned);
        it.remove();
        JobCounterUpdateEvent jce =
          new JobCounterUpdateEvent(assigned.attemptID.getTaskId().getJobId());
        jce.addCounterUpdate(JobCounter.OTHER_LOCAL_MAPS, 1);
        eventHandler.handle(jce);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Assigned based on * match");
        }
        LOG.info("CLONE DEBUG: EASY TO CAPTURE: "
        		+ "This ORIGINAL container's id is ["+ allocated.getId().toString() +"], "
        		+ "host is [" + host + "], rack is [" + rack + "], "
        		+ "this is a ANY level allocation, "
        		+ "whose job is ["+assigned.attemptID.getTaskId().getJobId()+"], "
        		+ "whose task attemp is ["+assigned.attemptID.toString()+"]");
      }
    }
  }

  @Private
  @VisibleForTesting
  class AssignedRequests {
    private final Map<ContainerId, TaskAttemptId> containerToAttemptMap =
      new HashMap<ContainerId, TaskAttemptId>();
    private final LinkedHashMap<TaskAttemptId, Set<Container>> maps = 
      new LinkedHashMap<TaskAttemptId, Set<Container>>();
    @VisibleForTesting
    final LinkedHashMap<TaskAttemptId, Set<Container>> reduces =
      new LinkedHashMap<TaskAttemptId, Set<Container>>();
    @VisibleForTesting
    final Set<TaskAttemptId> preemptionWaitingReduces =
      new HashSet<TaskAttemptId>();
    
    void add(Container container, TaskAttemptId tId) {
      LOG.info("Assigned container " + container.getId().toString() + " to " + tId);
      containerToAttemptMap.put(container.getId(), tId);
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
        if(maps.containsKey(tId)){
        	maps.get(tId).add(container);
        }
      } else {
        if(reduces.containsKey(tId)){
        	reduces.get(tId).add(container);
        }
      }
    }

    @SuppressWarnings("unchecked")
    void preemptReduce(int toPreempt) {
      List<TaskAttemptId> reduceList = new ArrayList<TaskAttemptId>
        (reduces.keySet());
      //sort reduces on progress
      Collections.sort(reduceList,
          new Comparator<TaskAttemptId>() {
        @Override
        public int compare(TaskAttemptId o1, TaskAttemptId o2) {
          return Float.compare(
              getJob().getTask(o1.getTaskId()).getAttempt(o1).getProgress(),
              getJob().getTask(o2.getTaskId()).getAttempt(o2).getProgress());
        }
      });
      
      for (int i = 0; i < toPreempt && reduceList.size() > 0; i++) {
        TaskAttemptId id = reduceList.remove(0);//remove the one on top
        LOG.info("Preempting " + id);
        preemptionWaitingReduces.add(id);
        eventHandler.handle(new TaskAttemptKillEvent(id, RAMPDOWN_DIAGNOSTIC));
      }
    }
    
    boolean remove(TaskAttemptId tId, ContainerId containerId) {
      boolean result = false;
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
    	  if(maps.containsKey(tId)){
    		  if(!maps.get(tId).isEmpty()){
    			  maps.get(tId).remove(containerId);
    			  result = true;
    		  }    		  
    	  }       
      } else {
    	if(reduces.containsKey(tId)){
    		if(!reduces.get(tId).isEmpty()){
    			reduces.get(tId).remove(containerId);
    			result = true;
    		}    		  
    	}
        if (result) {
          boolean preempted = preemptionWaitingReduces.remove(tId);
          if (preempted) {
            LOG.info("Reduce preemption successful " + tId);
          }
        }
      }      
      if (result) {
        containerToAttemptMap.remove(containerId);
      }
      return result;
    }
    
    TaskAttemptId get(ContainerId cId) {
      return containerToAttemptMap.get(cId);
    }

    Set<ContainerId> get(TaskAttemptId tId) {
      Set<Container> taskContainers;
      if (tId.getTaskId().getTaskType().equals(TaskType.MAP)) {
        taskContainers = maps.get(tId);
      } else {
        taskContainers= reduces.get(tId);
      }

      if (taskContainers == null) {
        return null;
      } else {
    	Set<ContainerId> taskContainerIds = new HashSet<ContainerId>();
    	for(Container taskContainer : taskContainers){
    		taskContainerIds.add(taskContainer.getId());
    	}
        return taskContainerIds;
      }
    }
  }

  private class ScheduleStats {
    int numPendingReduces;
    int numScheduledMaps;
    int numScheduledReduces;
    int numAssignedMaps;
    int numAssignedReduces;
    int numCompletedMaps;
    int numCompletedReduces;
    int numContainersAllocated;
    int numContainersReleased;

    public void updateAndLogIfChanged(String msgPrefix) {
      boolean changed = false;

      // synchronized to fix findbug warnings
      synchronized (RMContainerAllocator.this) {
        changed |= (numPendingReduces != pendingReduces.size());
        numPendingReduces = pendingReduces.size();
        changed |= (numScheduledMaps != scheduledRequests.maps.size());
        numScheduledMaps = scheduledRequests.maps.size();
        changed |= (numScheduledReduces != scheduledRequests.reduces.size());
        numScheduledReduces = scheduledRequests.reduces.size();
        changed |= (numAssignedMaps != assignedRequests.maps.size());
        numAssignedMaps = assignedRequests.maps.size();
        changed |= (numAssignedReduces != assignedRequests.reduces.size());
        numAssignedReduces = assignedRequests.reduces.size();
        changed |= (numCompletedMaps != getJob().getCompletedMaps());
        numCompletedMaps = getJob().getCompletedMaps();
        changed |= (numCompletedReduces != getJob().getCompletedReduces());
        numCompletedReduces = getJob().getCompletedReduces();
        changed |= (numContainersAllocated != containersAllocated);
        numContainersAllocated = containersAllocated;
        changed |= (numContainersReleased != containersReleased);
        numContainersReleased = containersReleased;
      }

      if (changed) {
        log(msgPrefix);
      }
    }

    public void log(String msgPrefix) {
        LOG.info(msgPrefix + "PendingReds:" + numPendingReduces +
        " ScheduledMaps:" + numScheduledMaps +
        " ScheduledReds:" + numScheduledReduces +
        " AssignedMaps:" + numAssignedMaps +
        " AssignedReds:" + numAssignedReduces +
        " CompletedMaps:" + numCompletedMaps +
        " CompletedReds:" + numCompletedReduces +
        " ContAlloc:" + numContainersAllocated +
        " ContRel:" + numContainersReleased +
        " HostLocal:" + hostLocalAssigned +
        " RackLocal:" + rackLocalAssigned);
    }
  }
}
