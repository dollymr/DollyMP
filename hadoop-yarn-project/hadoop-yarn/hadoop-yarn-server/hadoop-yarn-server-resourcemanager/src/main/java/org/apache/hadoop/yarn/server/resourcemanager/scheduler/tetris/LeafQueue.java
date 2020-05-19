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

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.tetris;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueState;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.nodelabels.CommonNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ActiveUsersManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.NodeType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceUsage;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerAppUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.tetris.FiCaSchedulerApp;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.tetris.FiCaSchedulerNode;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.server.utils.Lock;
import org.apache.hadoop.yarn.server.utils.Lock.NoLock;
import org.apache.hadoop.yarn.util.resource.Resources;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;

@Private
@Unstable
public class LeafQueue extends AbstractCSQueue {
  private static final Log LOG = LogFactory.getLog(LeafQueue.class);

  private float absoluteUsedCapacity = 0.0f;
  private int userLimit;
  private float userLimitFactor;

  protected int maxApplications;
  protected int maxApplicationsPerUser;
  
  private float maxAMResourcePerQueuePercent;
  
  private int nodeLocalityDelay;

  Set<FiCaSchedulerApp> activeApplications;
  Map<ApplicationAttemptId, FiCaSchedulerApp> applicationAttemptMap = 
      new HashMap<ApplicationAttemptId, FiCaSchedulerApp>();
  
  Set<FiCaSchedulerApp> pendingApplications;
  
  private float minimumAllocationFactor;

  private Map<String, User> users = new HashMap<String, User>();

  private final RecordFactory recordFactory = 
    RecordFactoryProvider.getRecordFactory(null);

  private CapacitySchedulerContext scheduler;
  
  private final ActiveUsersManager activeUsersManager;

  // cache last cluster resource to compute actual capacity
  private Resource lastClusterResource = Resources.none();
  
  // absolute capacity as a resource (based on cluster resource)
  private Resource absoluteCapacityResource = Resources.none();
  
  private final QueueHeadroomInfo queueHeadroomInfo = new QueueHeadroomInfo();
  
  private volatile float absoluteMaxAvailCapacity;
  
  //The original constructor
  public LeafQueue(CapacitySchedulerContext cs, 
      String queueName, CSQueue parent, CSQueue old) throws IOException {
    super(cs, queueName, parent, old);
    this.scheduler = cs;
    
    this.activeUsersManager = new ActiveUsersManager(metrics); 
    this.queueFinishTime = getFinishTime();
    this.queueWeight = getWeight();
    if(LOG.isDebugEnabled()) {
      LOG.debug("LeafQueue:" + " name=" + queueName
        + ", fullname=" + getQueuePath());
    }

    Comparator<FiCaSchedulerApp> activeApplicationComparator =
        cs.getApplicationComparator(1);
    Comparator<FiCaSchedulerApp> pendingApplicationComparator =
        cs.getApplicationComparator(0);
    this.pendingApplications = 
        new TreeSet<FiCaSchedulerApp>(pendingApplicationComparator);
    this.activeApplications = new TreeSet<FiCaSchedulerApp>(activeApplicationComparator);
    
    setupQueueConfigs(cs.getClusterResource());
  }
  
  //The constructor with finish time and weight
  public LeafQueue(CapacitySchedulerContext cs, 
	      String queueName, CSQueue parent, CSQueue old, long finishTime, 
	      long weight) throws IOException {
    super(cs, queueName, parent, old, finishTime, weight);
    this.scheduler = cs;

    this.activeUsersManager = new ActiveUsersManager(metrics); 
    if(LOG.isDebugEnabled()) {
      LOG.debug("LeafQueue:" + " name=" + queueName
        + ", fullname=" + getQueuePath());
    }

    Comparator<FiCaSchedulerApp> activeApplicationComparator =
        cs.getApplicationComparator(1);
    Comparator<FiCaSchedulerApp> pendingApplicationComparator =
        cs.getApplicationComparator(0);
    this.pendingApplications = 
        new TreeSet<FiCaSchedulerApp>(pendingApplicationComparator);
    this.activeApplications = new TreeSet<FiCaSchedulerApp>(activeApplicationComparator);
    
    setupQueueConfigs(cs.getClusterResource());
  }

  protected synchronized void setupQueueConfigs(Resource clusterResource)
      throws IOException {
    super.setupQueueConfigs(clusterResource);
    
    this.lastClusterResource = clusterResource;
    updateAbsoluteCapacityResource(clusterResource);
    
    // Initialize headroom info, also used for calculating application 
    // master resource limits.  Since this happens during queue initialization
    // and all queues may not be realized yet, we'll use (optimistic) 
    // absoluteMaxCapacity (it will be replaced with the more accurate 
    // absoluteMaxAvailCapacity during headroom/userlimit/allocation events)
    updateHeadroomInfo(clusterResource,
        queueCapacities.getAbsoluteMaximumCapacity());

    CapacitySchedulerConfiguration conf = csContext.getConfiguration();
    userLimit = conf.getUserLimit(getQueuePath());
    userLimitFactor = conf.getUserLimitFactor(getQueuePath());

    // Initially set to absoluteMax, will be updated to more accurate
    // max avail value during assignContainers
    absoluteMaxAvailCapacity = queueCapacities.getAbsoluteMaximumCapacity();

    maxApplications = conf.getMaximumApplicationsPerQueue(getQueuePath());
    if (maxApplications < 0) {
      int maxSystemApps = conf.getMaximumSystemApplications();
      maxApplications =
          (int) (maxSystemApps * queueCapacities.getAbsoluteCapacity());
    }
    maxApplicationsPerUser = 
      (int)(maxApplications * (userLimit / 100.0f) * userLimitFactor);
    
    maxAMResourcePerQueuePercent =
        conf.getMaximumApplicationMasterResourcePerQueuePercent(getQueuePath());

    if (!SchedulerUtils.checkQueueLabelExpression(
        this.accessibleLabels, this.defaultLabelExpression, null)) {
      throw new IOException("Invalid default label expression of "
          + " queue="
          + getQueueName()
          + " doesn't have permission to access all labels "
          + "in default label expression. labelExpression of resource request="
          + (this.defaultLabelExpression == null ? ""
              : this.defaultLabelExpression)
          + ". Queue labels="
          + (getAccessibleNodeLabels() == null ? "" : StringUtils.join(
              getAccessibleNodeLabels().iterator(), ',')));
    }
    
    nodeLocalityDelay = conf.getNodeLocalityDelay();

    this.minimumAllocationFactor =
        Resources.ratio(resourceCalculator,
            Resources.subtract(maximumAllocation, minimumAllocation),
            maximumAllocation);

    StringBuilder aclsString = new StringBuilder();
    for (Map.Entry<QueueACL, AccessControlList> e : acls.entrySet()) {
      aclsString.append(e.getKey() + ":" + e.getValue().getAclString());
    }

    StringBuilder labelStrBuilder = new StringBuilder(); 
    if (accessibleLabels != null) {
      for (String s : accessibleLabels) {
        labelStrBuilder.append(s);
        labelStrBuilder.append(",");
      }
    }

    LOG.info("Initializing " + queueName + "," +
        "capacity = " + queueCapacities.getCapacity() +
        " [= (float) configuredCapacity / 100 ]" + "," + 
        "asboluteCapacity = " + queueCapacities.getAbsoluteCapacity() +
        " [= parentAbsoluteCapacity * capacity ]" + "," +
        "maxCapacity = " + queueCapacities.getMaximumCapacity() +
        " [= configuredMaxCapacity ]" + "," +
        "absoluteMaxCapacity = " + queueCapacities.getAbsoluteMaximumCapacity() +
        " [= 1.0 maximumCapacity undefined, " +
        "(parentAbsoluteMaxCapacity * maximumCapacity) / 100 otherwise ]" + 
        "," +
        "userLimit = " + userLimit +
        " [= configuredUserLimit ]" + "," +
        "userLimitFactor = " + userLimitFactor +
        " [= configuredUserLimitFactor ]" + "," +
        "maxApplications = " + maxApplications +
        " [= configuredMaximumSystemApplicationsPerQueue or" + 
        " (int)(configuredMaximumSystemApplications * absoluteCapacity)]" + 
        "," +
        "maxApplicationsPerUser = " + maxApplicationsPerUser +
        " [= (int)(maxApplications * (userLimit / 100.0f) * " +
        "userLimitFactor) ]" + "," +
        "usedCapacity = " + queueCapacities.getUsedCapacity() +
        " [= usedResourcesMemory / " +
        "(clusterResourceMemory * absoluteCapacity)]" + "," +
        "absoluteUsedCapacity = " + absoluteUsedCapacity +
        " [= usedResourcesMemory / clusterResourceMemory]" + "," +
        "maxAMResourcePerQueuePercent = " + maxAMResourcePerQueuePercent +
        " [= configuredMaximumAMResourcePercent ]" + "," +
        "minimumAllocationFactor = " + minimumAllocationFactor +
        " [= (float)(maximumAllocationMemory - minimumAllocationMemory) / " +
        "maximumAllocationMemory ]" + "," +
        "numContainers = " + numContainers +
        " [= currentNumContainers ]" + "," +
        "state = " + state +
        " [= configuredState ]" + "," +
        "acls = " + aclsString +
        " [= configuredAcls ]" + "," + 
        "nodeLocalityDelay = " + nodeLocalityDelay + "," +
        "labels=" + labelStrBuilder.toString() + "," +
        "nodeLocalityDelay = " +  nodeLocalityDelay + "," +
        "reservationsContinueLooking = " +
        reservationsContinueLooking + ".");
  }
  
  @Override 
  public synchronized long getFinishTime(){
    long tmpFinishTime = 0;
	if(pendingApplications != null && !pendingApplications.isEmpty()){
	  for(FiCaSchedulerApp app : pendingApplications) {
	    //tmpFinishTime += app.getAppFinishTime();
		app.updateAppEffectiveWorkload();
		tmpFinishTime = tmpFinishTime < app.getAppEffectiveWorkload() 
		    ? app.getAppEffectiveWorkload() : tmpFinishTime;
	  }
	}
	if(activeApplications != null && !activeApplications.isEmpty()){
	  for(FiCaSchedulerApp app : activeApplications) {
		//tmpFinishTime += app.getAppFinishTime();
		app.updateAppEffectiveWorkload();
		tmpFinishTime = tmpFinishTime < app.getAppEffectiveWorkload() 
		    ? app.getAppEffectiveWorkload() : tmpFinishTime;
	  }
	}
	return tmpFinishTime;
  }
  
  @Override 
  public synchronized long getWeight(){
    long tmpWeight = 0;
	if(pendingApplications != null && !pendingApplications.isEmpty()){
	  for(FiCaSchedulerApp app : pendingApplications) {
		tmpWeight += app.getAppWeight();
		//tmpWeight = tmpWeight < app.getAppWeight() 
		      //? app.getAppWeight() : tmpWeight;
	  }
	}
	if(activeApplications != null && !activeApplications.isEmpty()){
	  for(FiCaSchedulerApp app : activeApplications) {
		tmpWeight += app.getAppWeight();
		//tmpWeight = tmpWeight < app.getAppWeight() 
		    //? app.getAppWeight() : tmpWeight;
	  }
	}
	return tmpWeight;
  }
  
  @Override
  public String getQueuePath() {
    return getParent().getQueuePath() + "." + getQueueName();
  }

  /**
   * Used only by tests.
   */
  @Private
  public float getMinimumAllocationFactor() {
    return minimumAllocationFactor;
  }
  
  /**
   * Used only by tests.
   */
  @Private
  public float getMaxAMResourcePerQueuePercent() {
    return maxAMResourcePerQueuePercent;
  }

  public int getMaxApplications() {
    return maxApplications;
  }

  public synchronized int getMaxApplicationsPerUser() {
    return maxApplicationsPerUser;
  }

  @Override
  public ActiveUsersManager getActiveUsersManager() {
    return activeUsersManager;
  }

  @Override
  public List<CSQueue> getChildQueues() {
    return null;
  }
  
  /**
   * Set user limit - used only for testing.
   * @param userLimit new user limit
   */
  synchronized void setUserLimit(int userLimit) {
    this.userLimit = userLimit;
  }

  /**
   * Set user limit factor - used only for testing.
   * @param userLimitFactor new user limit factor
   */
  synchronized void setUserLimitFactor(float userLimitFactor) {
    this.userLimitFactor = userLimitFactor;
  }

  @Override
  public synchronized int getNumApplications() {
    return getNumPendingApplications() + getNumActiveApplications();
  }

  public synchronized int getNumPendingApplications() {
    return pendingApplications.size();
  }

  public synchronized int getNumActiveApplications() {
    return activeApplications.size();
  }

  @Private
  public synchronized int getNumApplications(String user) {
    return getUser(user).getTotalApplications();
  }

  @Private
  public synchronized int getNumPendingApplications(String user) {
    return getUser(user).getPendingApplications();
  }

  @Private
  public synchronized int getNumActiveApplications(String user) {
    return getUser(user).getActiveApplications();
  }
  
  public synchronized int getNumContainers() {
    return numContainers;
  }

  @Override
  public synchronized QueueState getState() {
    return state;
  }

  @Private
  public synchronized int getUserLimit() {
    return userLimit;
  }

  @Private
  public synchronized float getUserLimitFactor() {
    return userLimitFactor;
  }

  @Override
  public synchronized QueueInfo getQueueInfo(
      boolean includeChildQueues, boolean recursive) {
    QueueInfo queueInfo = getQueueInfo();
    return queueInfo;
  }

  @Override
  public synchronized List<QueueUserACLInfo> 
  getQueueUserAclInfo(UserGroupInformation user) {
    QueueUserACLInfo userAclInfo = 
      recordFactory.newRecordInstance(QueueUserACLInfo.class);
    List<QueueACL> operations = new ArrayList<QueueACL>();
    for (QueueACL operation : QueueACL.values()) {
      if (hasAccess(operation, user)) {
        operations.add(operation);
      }
    }

    userAclInfo.setQueueName(getQueueName());
    userAclInfo.setUserAcls(operations);
    return Collections.singletonList(userAclInfo);
  }

  @Private
  public int getNodeLocalityDelay() {
    return nodeLocalityDelay;
  }
  
  public String toString() {
    return queueName + ": " + 
        "capacity=" + queueCapacities.getCapacity() + ", " + 
        "absoluteCapacity=" + queueCapacities.getAbsoluteCapacity() + ", " + 
        "usedResources=" + queueUsage.getUsed() +  ", " +
        "usedCapacity=" + getUsedCapacity() + ", " + 
        "absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + ", " +
        "numApps=" + getNumApplications() + ", " + 
        "numContainers=" + getNumContainers();  
  }
  
  @VisibleForTesting
  public synchronized void setNodeLabelManager(RMNodeLabelsManager mgr) {
    this.labelManager = mgr;
  }

  @VisibleForTesting
  public synchronized User getUser(String userName) {
    User user = users.get(userName);
    if (user == null) {
      user = new User();
      users.put(userName, user);
    }
    return user;
  }

  /**
   * @return an ArrayList of UserInfo objects who are active in this queue
   */
  public synchronized ArrayList<UserInfo> getUsers() {
    ArrayList<UserInfo> usersToReturn = new ArrayList<UserInfo>();
    for (Map.Entry<String, User> entry: users.entrySet()) {
      usersToReturn.add(new UserInfo(entry.getKey(), Resources.clone(
        entry.getValue().getUsed()), entry.getValue().getActiveApplications(),
        entry.getValue().getPendingApplications()));
    }
    return usersToReturn;
  }

  @Override
  public synchronized void reinitialize(
      CSQueue newlyParsedQueue, Resource clusterResource) 
  throws IOException {
    // Sanity check
    if (!(newlyParsedQueue instanceof LeafQueue) || 
        !newlyParsedQueue.getQueuePath().equals(getQueuePath())) {
      throw new IOException("Trying to reinitialize " + getQueuePath() + 
          " from " + newlyParsedQueue.getQueuePath());
    }

    setupQueueConfigs(clusterResource);

    // queue metrics are updated, more resource may be available
    // activate the pending applications if possible
    activateApplications();
  }

  @Override
  public void submitApplicationAttempt(FiCaSchedulerApp application,
      String userName) {
    // Careful! Locking order is important!
    synchronized (this) {
      User user = getUser(userName);
      // Add the attempt to our data-structures
      addApplicationAttempt(application, user);
    }

    // We don't want to update metrics for move app
    if (application.isPending()) {
      metrics.submitAppAttempt(userName);
    }
    getParent().submitApplicationAttempt(application, userName);
  }

  @Override
  public void submitApplication(ApplicationId applicationId, String userName,
      String queue)  throws AccessControlException {
    // Careful! Locking order is important!

    // Check queue ACLs
    UserGroupInformation userUgi = UserGroupInformation.createRemoteUser(userName);
    if (!hasAccess(QueueACL.SUBMIT_APPLICATIONS, userUgi)
        && !hasAccess(QueueACL.ADMINISTER_QUEUE, userUgi)) {
      throw new AccessControlException("User " + userName + " cannot submit" +
          " applications to queue " + getQueuePath());
    }

    User user = null;
    synchronized (this) {

      // Check if the queue is accepting jobs
      if (getState() != QueueState.RUNNING) {
        String msg = "Queue " + getQueuePath() +
        " is STOPPED. Cannot accept submission of application: " + applicationId;
        LOG.info(msg);
        throw new AccessControlException(msg);
      }

      // Check submission limits for queues
      if (getNumApplications() >= getMaxApplications()) {
        String msg = "Queue " + getQueuePath() + 
        " already has " + getNumApplications() + " applications," +
        " cannot accept submission of application: " + applicationId;
        LOG.info(msg);
        throw new AccessControlException(msg);
      }

      // Check submission limits for the user on this queue
      user = getUser(userName);
      if (user.getTotalApplications() >= getMaxApplicationsPerUser()) {
        String msg = "Queue " + getQueuePath() + 
        " already has " + user.getTotalApplications() + 
        " applications from user " + userName + 
        " cannot accept submission of application: " + applicationId;
        LOG.info(msg);
        throw new AccessControlException(msg);
      }
    }

    // Inform the parent queue
    try {
      getParent().submitApplication(applicationId, userName, queue);
    } catch (AccessControlException ace) {
      LOG.info("Failed to submit application to parent-queue: " + 
          getParent().getQueuePath(), ace);
      throw ace;
    }

  }
  
  public synchronized Resource getAMResourceLimit() {
     /* 
      * The limit to the amount of resources which can be consumed by
      * application masters for applications running in the queue
      * is calculated by taking the greater of the max resources currently
      * available to the queue (see absoluteMaxAvailCapacity) and the absolute
      * resources guaranteed for the queue and multiplying it by the am
      * resource percent.
      *
      * This is to allow a queue to grow its (proportional) application 
      * master resource use up to its max capacity when other queues are 
      * idle but to scale back down to it's guaranteed capacity as they 
      * become busy.
      *
      */
     Resource queueMaxCap;
     synchronized (queueHeadroomInfo) {
       queueMaxCap = queueHeadroomInfo.getQueueMaxCap();
     }
     Resource queueCap = Resources.max(resourceCalculator, lastClusterResource,
       absoluteCapacityResource, queueMaxCap);
     return Resources.multiplyAndNormalizeUp( 
          resourceCalculator,
          queueCap, 
          maxAMResourcePerQueuePercent, minimumAllocation);
  }
  
  public synchronized Resource getUserAMResourceLimit() {
     /*
      * The user amresource limit is based on the same approach as the 
      * user limit (as it should represent a subset of that).  This means that
      * it uses the absolute queue capacity instead of the max and is modified
      * by the userlimit and the userlimit factor as is the userlimit
      *
      */ 
     float effectiveUserLimit = Math.max(userLimit / 100.0f, 1.0f /    
       Math.max(getActiveUsersManager().getNumActiveUsers(), 1));
     
     return Resources.multiplyAndNormalizeUp( 
          resourceCalculator,
          absoluteCapacityResource, 
          maxAMResourcePerQueuePercent * effectiveUserLimit  *
            userLimitFactor, minimumAllocation);
  }

  private synchronized void activateApplications() {
    //limit of allowed resource usage for application masters
    Resource amLimit = getAMResourceLimit();
    Resource userAMLimit = getUserAMResourceLimit();
    LOG.info("We try to active applications from the pending queue");
    for (Iterator<FiCaSchedulerApp> i=pendingApplications.iterator(); 
         i.hasNext(); ) {
      FiCaSchedulerApp application = i.next();
      //LOG.info("In Pending Applications, this application is " + application.getApplicationId() + " and its workload is " + application.getAppEffectiveWorkload());
      // Check am resource limit
      Resource amIfStarted = 
        Resources.add(application.getAMResource(), queueUsage.getAMUsed());
      
      if (LOG.isDebugEnabled()) {
        LOG.debug("application AMResource " + application.getAMResource() +
          " maxAMResourcePerQueuePercent " + maxAMResourcePerQueuePercent +
          " amLimit " + amLimit +
          " lastClusterResource " + lastClusterResource +
          " amIfStarted " + amIfStarted);
      }
      
      if (!Resources.lessThanOrEqual(
        resourceCalculator, lastClusterResource, amIfStarted, amLimit)) {
        if (getNumActiveApplications() < 1) {
          LOG.warn("maximum-am-resource-percent is insufficient to start a" +
            " single application in queue, it is likely set too low." +
            " skipping enforcement to allow at least one application to start"); 
        } else {
          LOG.info("not starting application as amIfStarted exceeds amLimit");
          continue;
        }
      }
      
      // Check user am resource limit
      
      User user = getUser(application.getUser());
      
      Resource userAmIfStarted = 
        Resources.add(application.getAMResource(),
          user.getConsumedAMResources());
        
      if (!Resources.lessThanOrEqual(
          resourceCalculator, lastClusterResource, userAmIfStarted, 
          userAMLimit)) {
        if (getNumActiveApplications() < 1) {
          LOG.warn("maximum-am-resource-percent is insufficient to start a" +
            " single application in queue for user, it is likely set too low." +
            " skipping enforcement to allow at least one application to start"); 
        } else {
          LOG.info("not starting application as amIfStarted exceeds " +
            "userAmLimit");
          continue;
        }
      }
      LOG.info("Successfully add the application " + application.getApplicationId() + " to the active queue");
      user.activateApplication();
      activeApplications.add(application);
      queueUsage.incAMUsed(application.getAMResource());
      user.getResourceUsage().incAMUsed(application.getAMResource());
      i.remove();
      LOG.info("Application " + application.getApplicationId() +
          " from user: " + application.getUser() + 
          " activated in queue: " + getQueueName());
    }
  }
  
  private synchronized void addApplicationAttempt(FiCaSchedulerApp application,
      User user) {
    // Accept 
    user.submitApplication();
    pendingApplications.add(application);
    applicationAttemptMap.put(application.getApplicationAttemptId(), application);

    // Activate applications
    activateApplications();
    
    LOG.info("Application added -" +
        " appId: " + application.getApplicationId() +
        " user: " + user + "," + " leaf-queue: " + getQueueName() +
        " #user-pending-applications: " + user.getPendingApplications() +
        " #user-active-applications: " + user.getActiveApplications() +
        " #queue-pending-applications: " + getNumPendingApplications() +
        " #queue-active-applications: " + getNumActiveApplications()
        );
    String appIdList = "";
    for(FiCaSchedulerApp app : activeApplications){
    	appIdList+=(" "+app.getApplicationId().toString());
    }
    LOG.info("queue-active-applications contains" + appIdList);
  }

  @Override
  public void finishApplication(ApplicationId application, String user) {
    // Inform the activeUsersManager
    activeUsersManager.deactivateApplication(user, application);
    // Inform the parent queue
    getParent().finishApplication(application, user);
  }

  @Override
  public void finishApplicationAttempt(FiCaSchedulerApp application, String queue) {
    // Careful! Locking order is important!
    synchronized (this) {
      removeApplicationAttempt(application, getUser(application.getUser()));
    }
    getParent().finishApplicationAttempt(application, queue);
  }

  public synchronized void removeApplicationAttempt(
      FiCaSchedulerApp application, User user) {
    boolean wasActive = activeApplications.remove(application);
    if (!wasActive) {
      pendingApplications.remove(application);
    } else {
      queueUsage.decAMUsed(application.getAMResource());
      user.getResourceUsage().decAMUsed(application.getAMResource());
    }
    applicationAttemptMap.remove(application.getApplicationAttemptId());

    user.finishApplication(wasActive);
    if (user.getTotalApplications() == 0) {
      users.remove(application.getUser());
    }

    // Check if we can activate more applications
    activateApplications();

    LOG.info("Application removed -" +
        " appId: " + application.getApplicationId() + 
        " user: " + application.getUser() + 
        " queue: " + getQueueName() +
        " #user-pending-applications: " + user.getPendingApplications() +
        " #user-active-applications: " + user.getActiveApplications() +
        " #queue-pending-applications: " + getNumPendingApplications() +
        " #queue-active-applications: " + getNumActiveApplications()
        );
    LOG.info("Application removed -" +
        " if it is active ? " + wasActive);
    String appIdList = "";
    for(FiCaSchedulerApp app : activeApplications){
    	appIdList+=(" "+app.getApplicationId().toString());
    }
    LOG.info("queue-active-applications contains" + appIdList);
  }

  private synchronized FiCaSchedulerApp getApplication(
      ApplicationAttemptId applicationAttemptId) {
    return applicationAttemptMap.get(applicationAttemptId);
  }

  private static final CSAssignment NULL_ASSIGNMENT =
      new CSAssignment(Resources.createResource(0, 0), NodeType.NODE_LOCAL);
  
  private static final CSAssignment SKIP_ASSIGNMENT = new CSAssignment(true);
  
  private static Set<String> getRequestLabelSetByExpression(
      String labelExpression) {
    Set<String> labels = new HashSet<String>();
    if (null == labelExpression) {
      return labels;
    }
    for (String l : labelExpression.split("&&")) {
      if (l.trim().isEmpty()) {
        continue;
      }
      labels.add(l.trim());
    }
    return labels;
  }
  
  private synchronized double calculateEpsilonForSchedulingApps(){
	int totalTaskNum = 0;
	double totalTaskAlignment = 0;
	int totalJobNum = 0;
	long totalJobWorkload = 0;
	
	if(activeApplications != null && !activeApplications.isEmpty()){
		for(FiCaSchedulerApp app : activeApplications) {
			totalJobWorkload += app.getAppEffectiveWorkload();
			totalJobNum += 1;
			HashMap<String, Double> tmpAlignments = app.getAppTaskAlignments();
			for(Map.Entry<String, Double> taskAlignment : tmpAlignments.entrySet()){
				totalTaskAlignment += taskAlignment.getValue();
				totalTaskNum += 1;
			}
		}
	}
	
	double totalJobWorkloadDouble = Double.parseDouble(""+totalJobWorkload);
	double result = (totalTaskAlignment/totalTaskNum)/(totalJobWorkloadDouble/totalJobNum);
	if(Double.isNaN(result)){
		return 0;
	}else{
		return result;
	}
  }
  @Override
  public List<CSAssignment> assignContainers(Resource clusterResource,
	  FiCaSchedulerNode node, boolean needToUnreserve, float parentAbsoluteMaxAvailCapacity) {
    //We should not hold a lock on a queue and its parent concurrently - it
	//can lead to deadlocks when calls which walk down the tree occur
	//concurrently (getQueueInfo...)
	absoluteMaxAvailCapacity = CSQueueUtils.getAbsoluteMaxAvailCapacity(
	 resourceCalculator, clusterResource, this);
	return assignContainersInternal(clusterResource, node, needToUnreserve, absoluteMaxAvailCapacity);
  }
  
  private synchronized List<CSAssignment> assignContainersInternal(
    Resource clusterResource, FiCaSchedulerNode node, boolean needToUnreserve, float absoluteMaxAvailCapacity) {
	List<CSAssignment> assignList = new ArrayList<CSAssignment>();
    if(LOG.isDebugEnabled()) {
      LOG.debug("assignContainers: node=" + node.getNodeName()
        + " #applications=" + activeApplications.size());
    }
    
    // if our queue cannot access this node, just return
    if (!SchedulerUtils.checkQueueAccessToNode(accessibleLabels,
        node.getLabels())) {
    	assignList.add(NULL_ASSIGNMENT);
        return assignList;
    }
    
    // Check for reserved resources
    RMContainer reservedContainer = node.getReservedContainer();
    if (reservedContainer != null) {
      FiCaSchedulerApp application = 
          getApplication(reservedContainer.getApplicationAttemptId());
      synchronized (application) {
    	assignList.add(assignReservedContainer(application, node, reservedContainer,
                clusterResource));
        return assignList;
      }
    }
    
    float remainingAvailCapacity = Resources.ratio(resourceCalculator, node.getAvailableResource(), 
            Resources.multiply(clusterResource, absoluteMaxAvailCapacity));
    // Try to assign containers to applications in order
    // The actievApplications is one tree set with comparator, 
    // so it has been ordered by the priority
    Comparator<FiCaSchedulerApp> applicationComparator =
        this.scheduler.getApplicationComparator(2); // choose the tetris score comparator
    Set<FiCaSchedulerApp> activeApplicationsWithPriority = new TreeSet<FiCaSchedulerApp>(applicationComparator);
    
    double epsilon = this.calculateEpsilonForSchedulingApps();
    
    for(FiCaSchedulerApp app: activeApplications){
    	activeApplicationsWithPriority.add(app);
    }
    boolean hasSomethingAssigned = false;
    Iterator<FiCaSchedulerApp> it = activeApplicationsWithPriority.iterator();
    while (it.hasNext()) {
      FiCaSchedulerApp application = it.next();
      if(hasSomethingAssigned){
    	  break;
      }
      if(LOG.isDebugEnabled()) {
        LOG.debug("pre-assignContainers for application "
        + application.getApplicationId());
        application.showRequests();
      }

      synchronized (application) {
    	//LOG.info("We try to assign conatiners to application "+application.getApplicationAttemptId());
    	//LOG.info("The application workload is "+application.getAppEffectiveWorkload());
    	//LOG.info("The application tetris score is "+application.getAppTetrisScore());
    	// Check if this resource is on the blacklist
        if (SchedulerAppUtils.isBlacklisted(application, node, LOG)) {
            continue;
        }
        
    	Map<Priority, ResourceRequest> requestCash = new HashMap<Priority, ResourceRequest>();
    	
        Collection<Priority> priorities = application.getPriorities();        
        for(Priority requestPriority : priorities){
          ResourceRequest anyRequest =
                  application.getResourceRequest(requestPriority, ResourceRequest.ANY);
          requestCash.put(requestPriority, ResourceRequest.newInstance(
                  anyRequest.getPriority(), 
                  anyRequest.getResourceName(), 
                  anyRequest.getCapability(), 
                  anyRequest.getNumContainers(), 
                  anyRequest.getRelaxLocality(),
                  anyRequest.getNodeLabelExpression(),
                  anyRequest.getWorkload(),
                  anyRequest.getCopiesNumber(),
                  anyRequest.getTaskList()));         
        }

        application.updateAppEffectiveWorkloadForTetris(epsilon); //Then update the true score using our calculate epsilon
        // Schedule in priority order 
        for (Priority priority : priorities) {
        	//LOG.info("We try to assign containers to priority "+priority.getPriority());
        	ResourceRequest anyRequest =
	        		  requestCash.get(priority);
        	
            if (null == anyRequest) {
	            continue;
	        }
        	
	        if(anyRequest.getNumContainers()<=0){
	        	continue;
	        }
	        // Required resource
	        Resource required = anyRequest.getCapability();
	        
	        // Do we need containers at this 'priority'?
	        if (application.getTotalRequiredResources(priority) <= 0) {
	          continue;
	        }
	        if (!this.reservationsContinueLooking) {
	          if (!needContainers(application, priority, required)) {
	            if (LOG.isDebugEnabled()) {
	              LOG.debug("doesn't need containers based on reservation algo!");
	            }
	            continue;
	          }
	        }
	        //LOG.info("At this time, the cluster remaining available resource capacity is " + remainingAvailCapacity);
	        //LOG.info("At this time, the application ANY level request has container number is " + anyRequest.getNumContainers());
	        //LOG.info("At this time, the application ANY level request has task lit is " + anyRequest.getTaskList());
	        if(remainingAvailCapacity>0 && anyRequest.getNumContainers()>0){	          	                
	          Set<String> requestedNodeLabels =
	              getRequestLabelSetByExpression(anyRequest
	                  .getNodeLabelExpression());
	
	          // Compute user-limit & set headroom
	          // Note: We compute both user-limit & headroom with the highest 
	          //       priority request as the target. 
	          //       This works since we never assign lower priority requests
	          //       before all higher priority ones are serviced.
	          Resource userLimit = 
	              computeUserLimitAndSetHeadroom(application, clusterResource, 
	                  required, requestedNodeLabels);          
	          
	          // Check queue max-capacity limit
	          if (!canAssignToThisQueue(clusterResource, required,
	              node.getLabels(), application, true)) {
	        	  assignList.add(NULL_ASSIGNMENT);
	              return assignList;
	          }
	
	          // Check user limit
	          if (!assignToUser(clusterResource, application.getUser(), userLimit,
	              application, true, requestedNodeLabels)) {
	            break;
	          }
	          
	          // Inform the application it is about to get a scheduling opportunity
	          application.addSchedulingOpportunity(priority);
	          CSAssignment assignment = new CSAssignment(true);
	          // Try to schedule
	          String taskListString = "";
	          taskListString = anyRequest.getTaskList();
	          StringTokenizer st = new StringTokenizer(taskListString);
	          String taskId = null;
	          boolean needToUpdateRealRequestTaskList = false;
	          if(priority.getPriority() == 0 || priority.getPriority() == 5){
	            assignment =  
	              assignContainersOnNode(clusterResource, node, application, priority, 
	                null, needToUnreserve);
	          }else if((priority.getPriority() == 20)&&(anyRequest.getTaskList()!=null)&&(anyRequest.getNumContainers()>0)){	            
	            while(st.hasMoreTokens()){
	              taskId = st.nextToken();
	              if((taskId != null)&&application.getAppTaskList().containsKey(taskId)){
	            	  //LOG.info("application cashed request task list is = "+application.getAppTaskList()
	  	            //+ " and this turn the taskId is = "+taskId);
	            	  //LOG.info("application sent request task list is = "+taskListString);
	                if(application.getAppTaskList().get(taskId)-1>=0){
	                  //LOG.info("application "+application.getApplicationId()+ "is only any? "+application.isOnlyAny());
	                  assignment =  
		                      assignCloneContainersOnNode(clusterResource, node, application, priority, taskId, 
		                      	null, needToUnreserve, application.getIfHOSTCloneRound(), application.isOnlyAny());
	                  //Nothing meet for clone container, skip this taskId. Try to find next taskId.
	                  if(assignment.getSkipped()){
	                	  continue;
	                  }
	                  application.getAppTaskList().put(taskId, application.getAppTaskList().get(taskId)-1);
	                  needToUpdateRealRequestTaskList = false;
	                  break;
	                }else{
	                  assignment =  
		                          assignContainersOnNode(clusterResource, node, application, priority, 
		                            null, needToUnreserve);
	                  if (assignment.getSkipped()) {
		            	  continue;
		              }
	                  application.getAppTaskList().remove(taskId);
	                  application.getAppTaskAlignments().remove(taskId);
	                  needToUpdateRealRequestTaskList = true;
	                  break;
	                }
	              }
	              
	            }
	          }else if(anyRequest.getNumContainers() > 0){ // most likely this is one reduce task
	        	  assignment =  
	                assignContainersOnNode(clusterResource, node, application, priority, 
	                  null, needToUnreserve);
	        	  needToUpdateRealRequestTaskList = false;
	          }
	          // Did the application skip this node?
	          if (assignment.getSkipped()) {
	            // Don't count 'skipped nodes' as a scheduling opportunity!
	        	LOG.info("RM has decided to assign one skip container to application "+application.getApplicationAttemptId());
	            application.subtractSchedulingOpportunity(priority);
	            continue;
	          }
	          
	          // Did we schedule or reserve a container?
	          Resource assigned = assignment.getResource();
	          if (Resources.greaterThan(
	              resourceCalculator, clusterResource, assigned, Resources.none())) {
	
	            // Book-keeping 
	            // Note: Update headroom to account for current allocation too...
	            allocateResource(clusterResource, application, assigned,
	                node.getLabels());
	            
	            // Don't reset scheduling opportunities for non-local assignments
	            // otherwise the app will be delayed for each non-local assignment.
	            // This helps apps with many off-cluster requests schedule faster.
	            if (assignment.getType() != NodeType.OFF_SWITCH) {
	              if (LOG.isDebugEnabled()) {
	                LOG.debug("Resetting scheduling opportunities");
	              }
	              application.resetSchedulingOpportunities(priority);
	            }
	            assignList.add(assignment);
	            hasSomethingAssigned = true;
	            LOG.info("RM has decided to assign one container to application "+ application.getApplicationAttemptId());
	            if(taskId!=null && !taskId.equals("null")){
	            	String subTaskListString = taskListString.substring(taskId.length()+1);
	            	anyRequest.setTaskList(subTaskListString);
	            	if(needToUpdateRealRequestTaskList == true){
	            		application.getResourceRequest(priority, ResourceRequest.ANY).setTaskList(subTaskListString);
	            	}
	            }	            
	            anyRequest.setNumContainers(anyRequest.getNumContainers()-1);
	            requestCash.put(anyRequest.getPriority(), anyRequest);
	            if(application.getAppTaskHasAssignedList().containsKey(taskId)){
	            	application.getAppTaskHasAssignedList().replace(taskId, new Integer(1));
	            }
	            if(assignment.getType() == NodeType.NODE_LOCAL){
	            	if(application.getAppTaskHasAssignedHostList().containsKey(taskId)){
		            	application.getAppTaskHasAssignedHostList().replace(taskId, new Integer(1));
		            }
	            }
	            break;
	          } else {
	        	LOG.info("RM has decided to assign one empty container to task "+ application.getApplicationAttemptId());
	        	if(application.getAppTaskList().containsKey(taskId)){
	        		application.getAppTaskList().put(taskId, application.getAppTaskList().get(taskId)+1);
	        	}else{
	        		application.getAppTaskList().put(taskId, 0);
	        	}
	        	//No resource assigned
	            continue;
	        }
	      }
	    }
      }
    }
    
    //If after all normal and clone assignment, the list is empty, it means
    //all applications in this leaf queue is skipped on this node, so we 
    //return a list with only NULL_ASSIGNMENT.
    if(assignList.isEmpty()){
    	assignList.add(NULL_ASSIGNMENT);
    }else{
    	//LOG.info("RM assignment list length is " + assignList.size());
    }
    return assignList;
  }
  
  
  private synchronized CSAssignment 
  assignReservedContainer(FiCaSchedulerApp application, 
      FiCaSchedulerNode node, RMContainer rmContainer, Resource clusterResource) {
    // Do we still need this reservation?
    Priority priority = rmContainer.getReservedPriority();
    if (application.getTotalRequiredResources(priority) == 0) {
      // Release
      return new CSAssignment(application, rmContainer);
    }

    // Try to assign if we have sufficient resources
    assignContainersOnNode(clusterResource, node, application, priority, 
        rmContainer, false);
    
    // Doesn't matter... since it's already charged for at time of reservation
    // "re-reservation" is *free*
    return new CSAssignment(Resources.none(), NodeType.NODE_LOCAL);
  }
  
  protected Resource getHeadroom(User user, Resource queueMaxCap,
      Resource clusterResource, FiCaSchedulerApp application, Resource required) {
    return getHeadroom(user, queueMaxCap, clusterResource,
	  computeUserLimit(application, clusterResource, required, user, null));
  }
  
  private Resource getHeadroom(User user, Resource queueMaxCap,
      Resource clusterResource, Resource userLimit) {
    /** 
     * Headroom is:
     *    min(
     *        min(userLimit, queueMaxCap) - userConsumed,
     *        queueMaxCap - queueUsedResources
     *       )
     * 
     * ( which can be expressed as, 
     *  min (userLimit - userConsumed, queuMaxCap - userConsumed, 
     *    queueMaxCap - queueUsedResources)
     *  )
     *
     * given that queueUsedResources >= userConsumed, this simplifies to
     *
     * >> min (userlimit - userConsumed,   queueMaxCap - queueUsedResources) << 
     *
     */
    Resource headroom = 
      Resources.min(resourceCalculator, clusterResource,
        Resources.subtract(userLimit, user.getUsed()),
        Resources.subtract(queueMaxCap, queueUsage.getUsed())
        );
    return headroom;
  }

  synchronized boolean canAssignToThisQueue(Resource clusterResource,
      Resource required, Set<String> nodeLabels, FiCaSchedulerApp application, 
      boolean checkReservations) {
    // Get label of this queue can access, it's (nodeLabel AND queueLabel)
    Set<String> labelCanAccess;
    if (null == nodeLabels || nodeLabels.isEmpty()) {
      labelCanAccess = new HashSet<String>();
      // Any queue can always access any node without label
      labelCanAccess.add(RMNodeLabelsManager.NO_LABEL);
    } else {
      labelCanAccess = new HashSet<String>(Sets.intersection(accessibleLabels, nodeLabels));
    }
    
    boolean canAssign = true;
    for (String label : labelCanAccess) {
      Resource potentialTotalCapacity =
          Resources.add(queueUsage.getUsed(label), required);
      
      float potentialNewCapacity =
          Resources.divide(resourceCalculator, clusterResource,
              potentialTotalCapacity,
              labelManager.getResourceByLabel(label, clusterResource));
      // if enabled, check to see if could we potentially use this node instead
      // of a reserved node if the application has reserved containers
      // TODO, now only consider reservation cases when the node has no label
      if (this.reservationsContinueLooking && checkReservations
          && label.equals(RMNodeLabelsManager.NO_LABEL)) {
        float potentialNewWithoutReservedCapacity = Resources.divide(
            resourceCalculator,
            clusterResource,
            Resources.subtract(potentialTotalCapacity,
               application.getCurrentReservation()),
            labelManager.getResourceByLabel(label, clusterResource));

        if (potentialNewWithoutReservedCapacity <= queueCapacities
            .getAbsoluteMaximumCapacity()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("try to use reserved: "
                + getQueueName()
                + " usedResources: "
                + queueUsage.getUsed()
                + " clusterResources: "
                + clusterResource
                + " reservedResources: "
                + application.getCurrentReservation()
                + " currentCapacity "
                + Resources.divide(resourceCalculator, clusterResource,
                    queueUsage.getUsed(), clusterResource) + " required " + required
                + " potentialNewWithoutReservedCapacity: "
                + potentialNewWithoutReservedCapacity + " ( "
                + " max-capacity: "
                + queueCapacities.getAbsoluteMaximumCapacity() + ")");
          }
          // we could potentially use this node instead of reserved node
          return true;
        }
      }
      
      // Otherwise, if any of the label of this node beyond queue limit, we
      // cannot allocate on this node. Consider a small epsilon here.
      if (potentialNewCapacity > queueCapacities
          .getAbsoluteMaximumCapacity(label) + 1e-4) {
        canAssign = false;
        break;
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug(getQueueName()
            + "Check assign to queue, label=" + label
            + " usedResources: " + queueUsage.getUsed(label)
            + " clusterResources: " + clusterResource
            + " currentCapacity "
            + Resources.divide(resourceCalculator, clusterResource,
                queueUsage.getUsed(label),
                labelManager.getResourceByLabel(label, clusterResource))
            + " potentialNewCapacity: " + potentialNewCapacity + " ( "
            + " max-capacity: " + queueCapacities.getAbsoluteMaximumCapacity()
            + ")");
      }
    }
    
    return canAssign;
  }
  
  private Resource updateHeadroomInfo(Resource clusterResource, 
      float absoluteMaxAvailCapacity) {
  
    Resource queueMaxCap = 
      Resources.multiplyAndNormalizeDown(
          resourceCalculator, 
          clusterResource, 
          absoluteMaxAvailCapacity,
          minimumAllocation);

    synchronized (queueHeadroomInfo) {
      queueHeadroomInfo.setQueueMaxCap(queueMaxCap);
      queueHeadroomInfo.setClusterResource(clusterResource);
    }
    
    return queueMaxCap;
    
  }

  @Lock({LeafQueue.class, FiCaSchedulerApp.class})
  Resource computeUserLimitAndSetHeadroom(FiCaSchedulerApp application,
      Resource clusterResource, Resource required, Set<String> requestedLabels) {
    String user = application.getUser();
    User queueUser = getUser(user);

    // Compute user limit respect requested labels,
    // TODO, need consider headroom respect labels also
    Resource userLimit =
        computeUserLimit(application, clusterResource, required,
            queueUser, requestedLabels);
    
    Resource queueMaxCap = 
      updateHeadroomInfo(clusterResource, absoluteMaxAvailCapacity);
    
    Resource headroom =
        getHeadroom(queueUser, queueMaxCap, clusterResource, userLimit);
    
    if (LOG.isDebugEnabled()) {
      LOG.info("Headroom calculation for user " + user + ": " + 
          " userLimit=" + userLimit + 
          " queueMaxCap=" + queueMaxCap + 
          " consumed=" + queueUser.getUsed() + 
          " headroom=" + headroom);
    }
    
    CapacityHeadroomProvider headroomProvider = new CapacityHeadroomProvider(
      queueUser, this, application, required, queueHeadroomInfo);
    
    application.setTetrisHeadroomProvider(headroomProvider);

    metrics.setAvailableResourcesToUser(user, headroom);
    
    return userLimit;
  }
  
  @Lock(NoLock.class)
  private Resource computeUserLimit(FiCaSchedulerApp application,
      Resource clusterResource, Resource required, User user,
      Set<String> requestedLabels) {
    // What is our current capacity? 
    // * It is equal to the max(required, queue-capacity) if
    //   we're running below capacity. The 'max' ensures that jobs in queues
    //   with miniscule capacity (< 1 slot) make progress
    // * If we're running over capacity, then its
    //   (usedResources + required) (which extra resources we are allocating)
    Resource queueCapacity = Resource.newInstance(0, 0);
    if (requestedLabels != null && !requestedLabels.isEmpty()) {
      // if we have multiple labels to request, we will choose to use the first
      // label
      String firstLabel = requestedLabels.iterator().next();
      queueCapacity =
          Resources
              .max(resourceCalculator, clusterResource, queueCapacity,
                  Resources.multiplyAndNormalizeUp(resourceCalculator,
                      labelManager.getResourceByLabel(firstLabel,
                          clusterResource),
                      queueCapacities.getAbsoluteCapacity(firstLabel),
                      minimumAllocation));
    } else {
      // else there's no label on request, just to use absolute capacity as
      // capacity for nodes without label
      queueCapacity =
          Resources.multiplyAndNormalizeUp(resourceCalculator, labelManager
                .getResourceByLabel(CommonNodeLabelsManager.NO_LABEL, clusterResource),
              queueCapacities.getAbsoluteCapacity(), minimumAllocation);
    }

    // Allow progress for queues with miniscule capacity
    queueCapacity =
        Resources.max(
            resourceCalculator, clusterResource, 
            queueCapacity, 
            required);

    Resource currentCapacity =
        Resources.lessThan(resourceCalculator, clusterResource, 
            queueUsage.getUsed(), queueCapacity) ?
            queueCapacity : Resources.add(queueUsage.getUsed(), required);
    
    // Never allow a single user to take more than the 
    // queue's configured capacity * user-limit-factor.
    // Also, the queue's configured capacity should be higher than 
    // queue-hard-limit * ulMin
    
    final int activeUsers = activeUsersManager.getNumActiveUsers();  
    		
    Resource limit =
        Resources.roundUp(
            resourceCalculator, 
            Resources.min(
                resourceCalculator, clusterResource,   
                Resources.max(
                    resourceCalculator, clusterResource, 
                    Resources.divideAndCeil(
                        resourceCalculator, currentCapacity, activeUsers),
                    Resources.divideAndCeil(
                        resourceCalculator, 
                        Resources.multiplyAndRoundDown(
                            currentCapacity, userLimit), 
                        100)
                    ), 
                Resources.multiplyAndRoundDown(queueCapacity, userLimitFactor)
                ), 
            minimumAllocation);

    if (LOG.isDebugEnabled()) {
      String userName = application.getUser();
      LOG.debug("User limit computation for " + userName + 
          " in queue " + getQueueName() +
          " userLimit=" + userLimit +
          " userLimitFactor=" + userLimitFactor +
          " required: " + required + 
          " consumed: " + user.getUsed() + 
          " limit: " + limit +
          " queueCapacity: " + queueCapacity + 
          " qconsumed: " + queueUsage.getUsed() +
          " currentCapacity: " + currentCapacity +
          " activeUsers: " + activeUsers +
          " clusterCapacity: " + clusterResource
      );
    }

    return limit;
  }
  
  @Private
  protected synchronized boolean assignToUser(Resource clusterResource,
      String userName, Resource limit, FiCaSchedulerApp application,
      boolean checkReservations, Set<String> requestLabels) {
    User user = getUser(userName);
    
    String label = CommonNodeLabelsManager.NO_LABEL;
    if (requestLabels != null && !requestLabels.isEmpty()) {
      label = requestLabels.iterator().next();
    }

    // Note: We aren't considering the current request since there is a fixed
    // overhead of the AM, but it's a > check, not a >= check, so...
    if (Resources
        .greaterThan(resourceCalculator, clusterResource,
            user.getUsed(label),
            limit)) {
      // if enabled, check to see if could we potentially use this node instead
      // of a reserved node if the application has reserved containers
      if (this.reservationsContinueLooking && checkReservations) {
        if (Resources.lessThanOrEqual(
            resourceCalculator,
            clusterResource,
            Resources.subtract(user.getUsed(),
                application.getCurrentReservation()), limit)) {

          if (LOG.isDebugEnabled()) {
            LOG.debug("User " + userName + " in queue " + getQueueName()
                + " will exceed limit based on reservations - " + " consumed: "
                + user.getUsed() + " reserved: "
                + application.getCurrentReservation() + " limit: " + limit);
          }
          return true;
        }
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("User " + userName + " in queue " + getQueueName()
            + " will exceed limit - " + " consumed: "
            + user.getUsed() + " limit: " + limit);
      }
      return false;
    }
    return true;
  }

  boolean needContainers(FiCaSchedulerApp application, Priority priority,
      Resource required) {
    int requiredContainers = application.getTotalRequiredResources(priority);
    int reservedContainers = application.getNumReservedContainers(priority);
    int starvation = 0;
    if (reservedContainers > 0) {
      float nodeFactor = 
          Resources.ratio(
              resourceCalculator, required, getMaximumAllocation()
              );
      
      // Use percentage of node required to bias against large containers...
      // Protect against corner case where you need the whole node with
      // Math.min(nodeFactor, minimumAllocationFactor)
      starvation = 
          (int)((application.getReReservations(priority) / (float)reservedContainers) * 
                (1.0f - (Math.min(nodeFactor, getMinimumAllocationFactor())))
               );
      
      if (LOG.isDebugEnabled()) {
        LOG.debug("needsContainers:" +
            " app.#re-reserve=" + application.getReReservations(priority) + 
            " reserved=" + reservedContainers + 
            " nodeFactor=" + nodeFactor + 
            " minAllocFactor=" + getMinimumAllocationFactor() +
            " starvation=" + starvation);
      }
    }
    return (((starvation + requiredContainers) - reservedContainers) > 0);
  }

  private CSAssignment assignContainersOnNode(Resource clusterResource,
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority,
      RMContainer reservedContainer, boolean needToUnreserve) {
    Resource assigned = Resources.none();

    // Data-local
    ResourceRequest nodeLocalResourceRequest =
        application.getResourceRequest(priority, node.getNodeName());
    if (nodeLocalResourceRequest != null) {
      assigned = 
          assignNodeLocalContainers(clusterResource, nodeLocalResourceRequest, 
              node, application, priority, reservedContainer, needToUnreserve, false);
      if (Resources.greaterThan(resourceCalculator, clusterResource, 
          assigned, Resources.none())) {
    	LOG.info("Assign container with NODE_LOCAL level");
        return new CSAssignment(assigned, NodeType.NODE_LOCAL);
      }
    }
    
    // Rack-local
    ResourceRequest rackLocalResourceRequest =
        application.getResourceRequest(priority, node.getRackName());
    if (rackLocalResourceRequest != null) {
      if (!rackLocalResourceRequest.getRelaxLocality()) {
        return SKIP_ASSIGNMENT;
      }
      
      assigned = 
          assignRackLocalContainers(clusterResource, rackLocalResourceRequest, 
              node, application, priority, reservedContainer, needToUnreserve, false); //false
      if (Resources.greaterThan(resourceCalculator, clusterResource, 
          assigned, Resources.none())) {
    	LOG.info("Assign container with RACK_LOCAL level");
        return new CSAssignment(assigned, NodeType.RACK_LOCAL);
      }
    }
    
    // Off-switch
    ResourceRequest offSwitchResourceRequest =
        application.getResourceRequest(priority, ResourceRequest.ANY);
    if (offSwitchResourceRequest != null) {
      if (!offSwitchResourceRequest.getRelaxLocality()) {
        return SKIP_ASSIGNMENT;
      }
      LOG.info("Assign container with OFF_SWITCH level");
      return new CSAssignment(
          assignOffSwitchContainers(clusterResource, offSwitchResourceRequest,
              node, application, priority, reservedContainer, needToUnreserve, false), //false
              NodeType.OFF_SWITCH);
    }
    LOG.info("Assign container with SKIP_ASSIGNMENT");
    return SKIP_ASSIGNMENT;
  }

  private CSAssignment assignCloneContainersOnNode(Resource clusterResource,
	      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority, String taskId,
	      RMContainer reservedContainer, boolean needToUnreserve, boolean hostRound, boolean onlyAny) {
	    Resource assigned = Resources.none();
	    
	    //only have any level request
	    if(onlyAny){
	    	ResourceRequest offSwitchResourceRequest =
		            application.getResourceRequest(priority, ResourceRequest.ANY);
		    if (offSwitchResourceRequest != null) {
		      if (!offSwitchResourceRequest.getRelaxLocality()) {
		        return SKIP_ASSIGNMENT;
		      }
			    assigned = 
			       assignOffSwitchContainers(clusterResource, offSwitchResourceRequest, 
				       node, application, offSwitchResourceRequest.getPriority(), 
				       reservedContainer, needToUnreserve, true);
			    if (Resources.greaterThan(resourceCalculator, clusterResource, 
				   assigned, Resources.none())) {
			    	return new CSAssignment(assigned, NodeType.OFF_SWITCH);
			    }
		    }
		    return SKIP_ASSIGNMENT;
	    }
	    //not onlyAny
	    // Data-local
	    ResourceRequest nodeLocalResourceRequest =
	            application.getResourceRequest(priority, node.getNodeName());
	    if (nodeLocalResourceRequest != null && nodeLocalResourceRequest.getTaskList().contains(taskId)) {
		    assigned = 
		        assignNodeLocalContainers(clusterResource, nodeLocalResourceRequest, 
		            node, application, nodeLocalResourceRequest.getPriority(), 
		            reservedContainer, needToUnreserve, true); 
		    if (Resources.greaterThan(resourceCalculator, clusterResource, 
		        assigned, Resources.none())) {
		      return new CSAssignment(assigned, NodeType.NODE_LOCAL);
		    }
	    }
	    
	    //if no container assigned, no RACK and ANY clone container assigned.
	    if(application.getAppTaskHasAssignedList().containsKey(taskId)){
	    	if(application.getAppTaskHasAssignedList().get(taskId) == 0){
	    		return SKIP_ASSIGNMENT;
	    	}
	    }else{
	    	//Do not recognize this task
	    	return SKIP_ASSIGNMENT;
	    }
	    //if this is the Host Round, no RACK and ANY clone container assigned.
	    if(hostRound){
	    	return SKIP_ASSIGNMENT;
	    }
	    
	    // Rack-local
	    ResourceRequest rackLocalResourceRequest =
	            application.getResourceRequest(priority, node.getRackName());
	    if (rackLocalResourceRequest != null) {
	        if (!rackLocalResourceRequest.getRelaxLocality()) {
	          return SKIP_ASSIGNMENT;
	        }
		    assigned = 
		       assignRackLocalContainers(clusterResource, rackLocalResourceRequest, 
		           node, application, rackLocalResourceRequest.getPriority(), 
		           reservedContainer, needToUnreserve, true);
		    if (Resources.greaterThan(resourceCalculator, clusterResource, 
		       assigned, Resources.none())) {
		    	return new CSAssignment(assigned, NodeType.RACK_LOCAL);
		    }
	    }
	    
	    //if no HOST container assigned, no ANY rank clone container assigned.
	    if(application.getAppTaskHasAssignedHostList().containsKey(taskId)){
	    	if(application.getAppTaskHasAssignedHostList().get(taskId) == 0){
	    		return SKIP_ASSIGNMENT;
	    	}    	
	    }
	    // Off-switch
	    
	    ResourceRequest offSwitchResourceRequest =
	            application.getResourceRequest(priority, ResourceRequest.ANY);
	    if (offSwitchResourceRequest != null) {
	      if (!offSwitchResourceRequest.getRelaxLocality()) {
	        return SKIP_ASSIGNMENT;
	      }
		    assigned = 
		       assignOffSwitchContainers(clusterResource, offSwitchResourceRequest, 
			       node, application, offSwitchResourceRequest.getPriority(), 
			       reservedContainer, needToUnreserve, true);
		    if (Resources.greaterThan(resourceCalculator, clusterResource, 
			   assigned, Resources.none())) {
		    	return new CSAssignment(assigned, NodeType.OFF_SWITCH);
		    }
	    }
	    return SKIP_ASSIGNMENT;
	  }

  
  @Private
  protected boolean findNodeToUnreserve(Resource clusterResource,
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority,
      Resource capability) {
    // need to unreserve some other container first
    NodeId idToUnreserve = application.getNodeIdToUnreserve(priority, capability);
    if (idToUnreserve == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("checked to see if could unreserve for app but nothing "
            + "reserved that matches for this app");
      }
      return false;
    }
    FiCaSchedulerNode nodeToUnreserve = scheduler.getNode(idToUnreserve);
    if (nodeToUnreserve == null) {
      LOG.error("node to unreserve doesn't exist, nodeid: " + idToUnreserve);
      return false;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("unreserving for app: " + application.getApplicationId()
        + " on nodeId: " + idToUnreserve
        + " in order to replace reserved application and place it on node: "
        + node.getNodeID() + " needing: " + capability);
    }

    // headroom
    Resources.addTo(application.getHeadroom(), nodeToUnreserve
        .getReservedContainer().getReservedResource());

    // Make sure to not have completedContainers sort the queues here since
    // we are already inside an iterator loop for the queues and this would
    // cause an concurrent modification exception.
    completedContainer(clusterResource, application, nodeToUnreserve,
        nodeToUnreserve.getReservedContainer(),
        SchedulerUtils.createAbnormalContainerStatus(nodeToUnreserve
            .getReservedContainer().getContainerId(),
            SchedulerUtils.UNRESERVED_CONTAINER),
        RMContainerEventType.RELEASED, null, false);
    return true;
  }

  @Private
  protected boolean checkLimitsToReserve(Resource clusterResource,
      FiCaSchedulerApp application, Resource capability,
      boolean needToUnreserve) {
    if (needToUnreserve) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("we needed to unreserve to be able to allocate");
      }
      return false;
    }

    // we can't reserve if we got here based on the limit
    // checks assuming we could unreserve!!!
    Resource userLimit = computeUserLimitAndSetHeadroom(application,
        clusterResource, capability, null);

    // Check queue max-capacity limit,
    // TODO: Consider reservation on labels
    if (!canAssignToThisQueue(clusterResource, capability, null, application, false)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("was going to reserve but hit queue limit");
      }
      return false;
    }

    // Check user limit
    if (!assignToUser(clusterResource, application.getUser(), userLimit,
        application, false, null)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("was going to reserve but hit user limit");
      }
      return false;
    }
    return true;
  }


  private Resource assignNodeLocalContainers(Resource clusterResource,
      ResourceRequest nodeLocalResourceRequest, FiCaSchedulerNode node,
      FiCaSchedulerApp application, Priority priority,
      RMContainer reservedContainer, boolean needToUnreserve, boolean isForClone) {
    if (!isForClone && canAssign(application, priority, node, NodeType.NODE_LOCAL, 
        reservedContainer)) {
      return assignContainer(clusterResource, node, application, priority,
          nodeLocalResourceRequest, NodeType.NODE_LOCAL, reservedContainer,
          needToUnreserve,isForClone);
    }else if(isForClone){
    	return assignContainer(clusterResource, node, application, priority,
    	  nodeLocalResourceRequest, NodeType.NODE_LOCAL, reservedContainer,
    	  needToUnreserve,isForClone);
    }
    
    return Resources.none();
  }

  private Resource assignRackLocalContainers(
      Resource clusterResource, ResourceRequest rackLocalResourceRequest,  
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority,
      RMContainer reservedContainer, boolean needToUnreserve, boolean isForClone) {
    if (!isForClone && canAssign(application, priority, node, NodeType.RACK_LOCAL, 
        reservedContainer)) {
      return assignContainer(clusterResource, node, application, priority,
          rackLocalResourceRequest, NodeType.RACK_LOCAL, reservedContainer,
          needToUnreserve, isForClone);
    }else if(isForClone){
    	return assignContainer(clusterResource, node, application, priority,
    	  rackLocalResourceRequest, NodeType.RACK_LOCAL, reservedContainer,
    	  needToUnreserve,isForClone);
    }
    
    return Resources.none();
  }

  private Resource assignOffSwitchContainers(
      Resource clusterResource, ResourceRequest offSwitchResourceRequest,
      FiCaSchedulerNode node, FiCaSchedulerApp application, Priority priority, 
      RMContainer reservedContainer, boolean needToUnreserve, boolean isForClone) {
    if (!isForClone && canAssign(application, priority, node, NodeType.OFF_SWITCH, 
        reservedContainer)) {
      return assignContainer(clusterResource, node, application, priority,
          offSwitchResourceRequest, NodeType.OFF_SWITCH, reservedContainer,
          needToUnreserve, isForClone);
    }else if(isForClone){
      return assignContainer(clusterResource, node, application, priority,
        offSwitchResourceRequest, NodeType.OFF_SWITCH, reservedContainer,
        needToUnreserve, isForClone);
    }
    
    return Resources.none();
  }

  boolean canAssign(FiCaSchedulerApp application, Priority priority, 
      FiCaSchedulerNode node, NodeType type, RMContainer reservedContainer) {

    // Clearly we need containers for this application...
    if (type == NodeType.OFF_SWITCH) {
      if (reservedContainer != null) {
    	LOG.info("We true can assign OFF_SWITCH "+ priority.getPriority() +" contaienr on node "+ node.getNodeName());
        return true;
      }

      // 'Delay' off-switch
      ResourceRequest offSwitchRequest = 
          application.getResourceRequest(priority, ResourceRequest.ANY);
      long missedOpportunities = application.getSchedulingOpportunities(priority);
      long requiredContainers = offSwitchRequest.getNumContainers(); 
      
      float localityWaitFactor = 
        application.getLocalityWaitFactor(priority, 
            scheduler.getNumClusterNodes());
      boolean result = ((requiredContainers * localityWaitFactor) < missedOpportunities);
      LOG.info("We "+ result +" can assign OFF_SWITCH "+ priority.getPriority() +" contaienr on node "+ node.getNodeName());
      return result;
    }

    // Check if we need containers on this rack 
    ResourceRequest rackLocalRequest = 
      application.getResourceRequest(priority, node.getRackName());
    if (rackLocalRequest == null || rackLocalRequest.getNumContainers() <= 0) {
    	LOG.info("We false can assign RACK_LOCAL "+ priority.getPriority() +" contaienr on node "+ node.getNodeName());
      return false;
    }
      
    // If we are here, we do need containers on this rack for RACK_LOCAL req
    if (type == NodeType.RACK_LOCAL) {
      // 'Delay' rack-local just a little bit...
      long missedOpportunities = application.getSchedulingOpportunities(priority);
      boolean result = (
              Math.min(scheduler.getNumClusterNodes(), getNodeLocalityDelay()) < 
              missedOpportunities
              );
      LOG.info("We "+ result +" can assign RACK_LOCAL "+ priority.getPriority() +" contaienr on node "+ node.getNodeName());
      return result;
    }

    // Check if we need containers on this host
    if (type == NodeType.NODE_LOCAL) {
      // Now check if we need containers on this host...
      ResourceRequest nodeLocalRequest = 
        application.getResourceRequest(priority, node.getNodeName());
      if (nodeLocalRequest != null) {
    	boolean result = nodeLocalRequest.getNumContainers() > 0;
    	LOG.info("We "+ result +" can assign NODE_LOCAL "+ priority.getPriority() +" contaienr on node "+ node.getNodeName());
        return result;
      }
    }
    LOG.info("We false can assign ANY_TYPE "+ priority.getPriority() +" contaienr on node "+ node.getNodeName());
    return false;
  }
  
  private Container getContainer(RMContainer rmContainer, 
      FiCaSchedulerApp application, FiCaSchedulerNode node, 
      Resource capability, Priority priority) {
    return (rmContainer != null) ? rmContainer.getContainer() :
      createContainer(application, node, capability, priority);
  }

  Container createContainer(FiCaSchedulerApp application, FiCaSchedulerNode node, 
      Resource capability, Priority priority) {
  
    NodeId nodeId = node.getRMNode().getNodeID();
    ContainerId containerId = BuilderUtils.newContainerId(application
        .getApplicationAttemptId(), application.getNewContainerId());
  
    // Create the container
    Container container =
        BuilderUtils.newContainer(containerId, nodeId, node.getRMNode()
          .getHttpAddress(), capability, priority, null);
  
    return container;
  }


  private Resource assignContainer(Resource clusterResource, FiCaSchedulerNode node, 
      FiCaSchedulerApp application, Priority priority, 
      ResourceRequest request, NodeType type, RMContainer rmContainer,
      boolean needToUnreserve, boolean isForClone) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("assignContainers: node=" + node.getNodeName()
        + " application=" + application.getApplicationId()
        + " priority=" + priority.getPriority()
        + " request=" + request + " type=" + type
        + " needToUnreserve= " + needToUnreserve
        + " isForClone= " + isForClone);
    }
    // check if the resource request can access the label
    if (!SchedulerUtils.checkNodeLabelExpression(
        node.getLabels(),
        request.getNodeLabelExpression())) {
      // this is a reserved container, but we cannot allocate it now according
      // to label not match. This can be caused by node label changed
      // We should un-reserve this container.
      if (rmContainer != null) {
        unreserve(application, priority, node, rmContainer);
      }
      return Resources.none();
    }
    Resource capability = request.getCapability();
    Resource available = node.getAvailableResource();
    Resource totalResource = node.getTotalResource();

    if (!Resources.fitsIn(capability, totalResource)) {
      LOG.warn("Node : " + node.getNodeID()
          + " does not have sufficient resource for request : " + request
          + " node total capability : " + node.getTotalResource());
      return Resources.none();
    }

    assert Resources.greaterThan(
        resourceCalculator, clusterResource, available, Resources.none());

    // Create the container if necessary
    Container container = 
        getContainer(rmContainer, application, node, capability, priority);

    // something went wrong getting/creating the container 
    if (container == null) {
      LOG.warn("Couldn't get container for allocation!");
      return Resources.none();
    }

    // default to true since if reservation continue look feature isn't on
    // needContainers is checked earlier and we wouldn't have gotten this far
    boolean canAllocContainer = true;
    if (this.reservationsContinueLooking) {
      // based on reservations can we allocate/reserve more or do we need
      // to unreserve one first
      canAllocContainer = needContainers(application, priority, capability);
      if (LOG.isDebugEnabled()) {
        LOG.debug("can alloc container is: " + canAllocContainer);
      }
    }

    // Can we allocate a container on this node?
    int availableContainers = 
        resourceCalculator.computeAvailableContainers(available, capability);
    if (availableContainers > 0) {
      // Allocate...

      // Did we previously reserve containers at this 'priority'?
      if (rmContainer != null) {
        unreserve(application, priority, node, rmContainer);
      } else if (this.reservationsContinueLooking
          && (!canAllocContainer || needToUnreserve)) {
        // need to unreserve some other container first
        boolean res = findNodeToUnreserve(clusterResource, node, application,
            priority, capability);
        if (!res) {
          return Resources.none();
        }
      } else {
        // we got here by possibly ignoring queue capacity limits. If the
        // parameter needToUnreserve is true it means we ignored one of those
        // limits in the chance we could unreserve. If we are here we aren't
        // trying to unreserve so we can't allocate anymore due to that parent
        // limit.
        if (needToUnreserve) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("we needed to unreserve to be able to allocate, skipping");
          }
          return Resources.none();
        }
      }

      // Inform the application
      RMContainer allocatedContainer = 
          application.allocate(type, node, priority, request, container, isForClone);

      // Does the application need this resource?
      if (allocatedContainer == null) {
        return Resources.none();
      }

      // Inform the node
      node.allocateContainer(allocatedContainer);

      LOG.info("assignedContainer" +
          " application attempt=" + application.getApplicationAttemptId() +
          " container=" + container + 
          " queue=" + this + 
          " clusterResource=" + clusterResource);

      return container.getResource();
    } else {
      // if we are allowed to allocate but this node doesn't have space, reserve it or
      // if this was an already a reserved container, reserve it again
      if ((canAllocContainer) || (rmContainer != null)) {

        if (reservationsContinueLooking) {
          // we got here by possibly ignoring parent queue capacity limits. If
          // the parameter needToUnreserve is true it means we ignored one of
          // those limits in the chance we could unreserve. If we are here
          // we aren't trying to unreserve so we can't allocate
          // anymore due to that parent limit
          boolean res = checkLimitsToReserve(clusterResource, application, capability, 
              needToUnreserve);
          if (!res) {
            return Resources.none();
          }
        }

        // Reserve by 'charging' in advance...
        if(!isForClone){
        	reserve(application, priority, node, rmContainer, container);
        
        	LOG.info("Reserved container " + 
        			" application=" + application.getApplicationId() + 
        			" resource=" + request.getCapability() + 
        			" queue=" + this.toString() + 
        			" usedCapacity=" + getUsedCapacity() + 
        			" absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + 
        			" used=" + queueUsage.getUsed() +
        			" cluster=" + clusterResource);

        	return request.getCapability();
        }

      }
      return Resources.none();
    }
  }

  private void reserve(FiCaSchedulerApp application, Priority priority, 
      FiCaSchedulerNode node, RMContainer rmContainer, Container container) {
    // Update reserved metrics if this is the first reservation
    if (rmContainer == null) {
      getMetrics().reserveResource(
          application.getUser(), container.getResource());
    }

    // Inform the application 
    rmContainer = application.reserve(node, priority, rmContainer, container);
    
    // Update the node
    node.reserveResource(application, priority, rmContainer);
  }

  private boolean unreserve(FiCaSchedulerApp application, Priority priority,
      FiCaSchedulerNode node, RMContainer rmContainer) {
    // Done with the reservation?
    if (application.unreserve(node, priority)) {
      node.unreserveResource(application);

      // Update reserved metrics
      getMetrics().unreserveResource(application.getUser(),
          rmContainer.getContainer().getResource());
      return true;
    }
    return false;
  }

  @Override
  public void completedContainer(Resource clusterResource, 
      FiCaSchedulerApp application, FiCaSchedulerNode node, RMContainer rmContainer, 
      ContainerStatus containerStatus, RMContainerEventType event, CSQueue childQueue,
      boolean sortQueues) {
    if (application != null) {

      boolean removed = false;

      // Careful! Locking order is important!
      synchronized (this) {

        Container container = rmContainer.getContainer();

        // Inform the application & the node
        // Note: It's safe to assume that all state changes to RMContainer
        // happen under scheduler's lock... 
        // So, this is, in effect, a transaction across application & node
        if (rmContainer.getState() == RMContainerState.RESERVED) {
          removed = unreserve(application, rmContainer.getReservedPriority(),
              node, rmContainer);
        } else {
          removed =
            application.containerCompleted(rmContainer, containerStatus, event);
          node.releaseContainer(container);
        }

        // Book-keeping
        if (removed) {
          releaseResource(clusterResource, application,
              container.getResource(), node.getLabels());
          LOG.info("completedContainer" +
              " container=" + container +
              " queue=" + this +
              " cluster=" + clusterResource);
        }
      }

      if (removed) {
        // Inform the parent queue _outside_ of the leaf-queue lock
        getParent().completedContainer(clusterResource, application, node,
          rmContainer, null, event, this, sortQueues);
      }
    }
  }

  synchronized void allocateResource(Resource clusterResource,
      SchedulerApplicationAttempt application, Resource resource,
      Set<String> nodeLabels) {
    super.allocateResource(clusterResource, resource, nodeLabels);
    
    // Update user metrics
    String userName = application.getUser();
    User user = getUser(userName);
    user.assignContainer(resource, nodeLabels);
    // Note this is a bit unconventional since it gets the object and modifies
    // it here, rather then using set routine
    Resources.subtractFrom(application.getHeadroom(), resource); // headroom
    metrics.setAvailableResourcesToUser(userName, application.getHeadroom());
    
    if (LOG.isDebugEnabled()) {
      LOG.info(getQueueName() + 
          " user=" + userName + 
          " used=" + queueUsage.getUsed() + " numContainers=" + numContainers +
          " headroom = " + application.getHeadroom() +
          " user-resources=" + user.getUsed()
          );
    }
  }

  synchronized void releaseResource(Resource clusterResource, 
      FiCaSchedulerApp application, Resource resource, Set<String> nodeLabels) {
    super.releaseResource(clusterResource, resource, nodeLabels);
    
    // Update user metrics
    String userName = application.getUser();
    User user = getUser(userName);
    user.releaseContainer(resource, nodeLabels);
    metrics.setAvailableResourcesToUser(userName, application.getHeadroom());
      
    LOG.info(getQueueName() + 
        " used=" + queueUsage.getUsed() + " numContainers=" + numContainers + 
        " user=" + userName + " user-resources=" + user.getUsed());
  }
  
  private void updateAbsoluteCapacityResource(Resource clusterResource) {
    absoluteCapacityResource =
        Resources.multiplyAndNormalizeUp(resourceCalculator, clusterResource,
            queueCapacities.getAbsoluteCapacity(), minimumAllocation);
  }

  @Override
  public synchronized void updateClusterResource(Resource clusterResource) {
    lastClusterResource = clusterResource;
    updateAbsoluteCapacityResource(clusterResource);
    
    // Update headroom info based on new cluster resource value
    // absoluteMaxCapacity now,  will be replaced with absoluteMaxAvailCapacity
    // during allocation
    updateHeadroomInfo(clusterResource,
        queueCapacities.getAbsoluteMaximumCapacity());
    
    // Update metrics
    CSQueueUtils.updateQueueStatistics(
        resourceCalculator, this, getParent(), clusterResource, 
        minimumAllocation);

    // queue metrics are updated, more resource may be available
    // activate the pending applications if possible
    activateApplications();

    // Update application properties
    for (FiCaSchedulerApp application : activeApplications) {
      synchronized (application) {
        computeUserLimitAndSetHeadroom(application, clusterResource, 
            Resources.none(), null);
      }
    }
  }

  @VisibleForTesting
  public static class User {
    ResourceUsage userResourceUsage = new ResourceUsage();
    int pendingApplications = 0;
    int activeApplications = 0;

    public ResourceUsage getResourceUsage() {
      return userResourceUsage;
    }
    
    public Resource getUsed() {
      return userResourceUsage.getUsed();
    }
    
    public Resource getUsed(String label) {
      return userResourceUsage.getUsed(label);
    }

    public int getPendingApplications() {
      return pendingApplications;
    }

    public int getActiveApplications() {
      return activeApplications;
    }
    
    public Resource getConsumedAMResources() {
      return userResourceUsage.getAMUsed();
    }

    public int getTotalApplications() {
      return getPendingApplications() + getActiveApplications();
    }
    
    public synchronized void submitApplication() {
      ++pendingApplications;
    }
    
    public synchronized void activateApplication() {
      --pendingApplications;
      ++activeApplications;
    }

    public synchronized void finishApplication(boolean wasActive) {
      if (wasActive) {
        --activeApplications;
      }
      else {
        --pendingApplications;
      }
    }

    public void assignContainer(Resource resource,
        Set<String> nodeLabels) {
      if (nodeLabels == null || nodeLabels.isEmpty()) {
        userResourceUsage.incUsed(resource);
      } else {
        for (String label : nodeLabels) {
          userResourceUsage.incUsed(label, resource);
        }
      }
    }

    public void releaseContainer(Resource resource, Set<String> nodeLabels) {
      if (nodeLabels == null || nodeLabels.isEmpty()) {
        userResourceUsage.decUsed(resource);
      } else {
        for (String label : nodeLabels) {
          userResourceUsage.decUsed(label, resource);
        }
      }
    }
  }

  @Override
  public void recoverContainer(Resource clusterResource,
      SchedulerApplicationAttempt attempt, RMContainer rmContainer) {
    if (rmContainer.getState().equals(RMContainerState.COMPLETED)) {
      return;
    }
    // Careful! Locking order is important! 
    synchronized (this) {
      FiCaSchedulerNode node =
          scheduler.getNode(rmContainer.getContainer().getNodeId());
      allocateResource(clusterResource, attempt, rmContainer.getContainer()
          .getResource(), node.getLabels());
    }
    getParent().recoverContainer(clusterResource, attempt, rmContainer);
  }

  /**
   * Obtain (read-only) collection of active applications.
   */
  public Set<FiCaSchedulerApp> getApplications() {
    // need to access the list of apps from the preemption monitor
    return activeApplications;
  }

  // return a single Resource capturing the overal amount of pending resources
  public synchronized Resource getTotalResourcePending() {
    Resource ret = BuilderUtils.newResource(0, 0);
    for (FiCaSchedulerApp f : activeApplications) {
      Resources.addTo(ret, f.getTotalPendingRequests());
    }
    return ret;
  }

  @Override
  public synchronized void collectSchedulerApplications(
      Collection<ApplicationAttemptId> apps) {
    for (FiCaSchedulerApp pendingApp : pendingApplications) {
      apps.add(pendingApp.getApplicationAttemptId());
    }
    for (FiCaSchedulerApp app : activeApplications) {
      apps.add(app.getApplicationAttemptId());
    }
  }

  @Override
  public void attachContainer(Resource clusterResource,
      FiCaSchedulerApp application, RMContainer rmContainer) {
    if (application != null) {
      FiCaSchedulerNode node =
          scheduler.getNode(rmContainer.getContainer().getNodeId());
      allocateResource(clusterResource, application, rmContainer.getContainer()
          .getResource(), node.getLabels());
      LOG.info("movedContainer" + " container=" + rmContainer.getContainer()
          + " resource=" + rmContainer.getContainer().getResource()
          + " queueMoveIn=" + this + " usedCapacity=" + getUsedCapacity()
          + " absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + " used="
          + queueUsage.getUsed() + " cluster=" + clusterResource);
      // Inform the parent queue
      getParent().attachContainer(clusterResource, application, rmContainer);
    }
  }

  @Override
  public void detachContainer(Resource clusterResource,
      FiCaSchedulerApp application, RMContainer rmContainer) {
    if (application != null) {
      FiCaSchedulerNode node =
          scheduler.getNode(rmContainer.getContainer().getNodeId());
      releaseResource(clusterResource, application, rmContainer.getContainer()
          .getResource(), node.getLabels());
      LOG.info("movedContainer" + " container=" + rmContainer.getContainer()
          + " resource=" + rmContainer.getContainer().getResource()
          + " queueMoveOut=" + this + " usedCapacity=" + getUsedCapacity()
          + " absoluteUsedCapacity=" + getAbsoluteUsedCapacity() + " used="
          + queueUsage.getUsed() + " cluster=" + clusterResource);
      // Inform the parent queue
      getParent().detachContainer(clusterResource, application, rmContainer);
    }
  }
  
  public void setCapacity(float capacity) {
    queueCapacities.setCapacity(capacity);
  }

  public void setAbsoluteCapacity(float absoluteCapacity) {
    queueCapacities.setAbsoluteCapacity(absoluteCapacity);
  }

  public void setMaxApplications(int maxApplications) {
    this.maxApplications = maxApplications;
  }
  
  /*
   * Holds shared values used by all applications in
   * the queue to calculate headroom on demand
   */
  static class QueueHeadroomInfo {
    private Resource queueMaxCap;
    private Resource clusterResource;
    
    public void setQueueMaxCap(Resource queueMaxCap) {
      this.queueMaxCap = queueMaxCap;
    }
    
    public Resource getQueueMaxCap() {
      return queueMaxCap;
    }
    
    public void setClusterResource(Resource clusterResource) {
      this.clusterResource = clusterResource;
    }
    
    public Resource getClusterResource() {
      return clusterResource;
    }
  }
}
