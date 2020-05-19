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

package org.apache.hadoop.mapreduce.v2.api.records;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <p>
 * <code>TaskAttemptId</code> represents the unique identifier for a task
 * attempt. Each task attempt is one particular instance of a Map or Reduce Task
 * identified by its TaskId.
 * </p>
 * 
 * <p>
 * TaskAttemptId consists of 2 parts. First part is the <code>TaskId</code>,
 * that this <code>TaskAttemptId</code> belongs to. Second part is the task
 * attempt number.
 * </p>
 */
public abstract class TaskAttemptId implements Comparable<TaskAttemptId> {
	
  static final Log LOG = LogFactory.getLog(TaskAttemptId.class);
  
  //original is null, clone copy is its parentID
  private TaskAttemptId parentID = null;

  //The relevant set of the task attempt copies.
  //The original contains all clone copies, the clone set has nothing.
  private final Set<TaskAttemptId> cloneAttemptSet = new HashSet<TaskAttemptId>();

  /**
   * @return the associated TaskId.
   */
  public abstract TaskId getTaskId();

  /**
   * @return the attempt id.
   */
  public abstract int getId();

  public abstract void setTaskId(TaskId taskId);

  public abstract void setId(int id);

  protected static final String TASKATTEMPT = "attempt";
  
  public TaskAttemptId getParentID(){
	  return parentID;
  }
  
  public void setParentID(TaskAttemptId id){
	  this.parentID = id;
  }
  
  /**
   * Add one task attempt ID to the relevant set
   */
  public Set<TaskAttemptId> getCloneAttemptSet(){
	  return this.cloneAttemptSet;
  }
  
  /**
   * Add one task attempt ID to the relevant set
   */
  public void addAttemptIDToSet(TaskAttemptId id){
	  this.cloneAttemptSet.add(id);
  }
  
  /**
   * Remove one task attempt ID to the relevant set
   */
  public void removeAttemptIDToSet(TaskAttemptId id){
	  this.cloneAttemptSet.remove(id);
  }
  
  public Set<TaskAttemptId> getRelevantAttemptId(){
	  Set<TaskAttemptId> result = new HashSet<TaskAttemptId>();	  
	  result.add(this);
	  
	  if(this.getParentID() == null){
		  if(!this.getCloneAttemptSet().isEmpty()){
			  result.addAll(this.getCloneAttemptSet());
		  }
	  }else{		  
		  result.add(this.getParentID());
		  result.addAll(this.getCloneAttemptSet());
		  if(!this.getCloneAttemptSet().isEmpty()){
			  result.addAll(this.getCloneAttemptSet());
			  if(LOG.isWarnEnabled()){
				  LOG.warn("this attempt " + this.toString() +" has both original"
				    +" attempt and also clone attempts, which is illegal, AM has clone"
				    + "issue.");
			  }
		  }
	  }	  
	  return result;
  }
  
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + getId();
    result =
        prime * result + ((getTaskId() == null) ? 0 : getTaskId().hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    TaskAttemptId other = (TaskAttemptId) obj;
    if (getId() != other.getId())
      return false;
    if (!getTaskId().equals(other.getTaskId()))
      return false;
    return true;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder(TASKATTEMPT);
    TaskId taskId = getTaskId();
    builder.append("_").append(
        taskId.getJobId().getAppId().getClusterTimestamp());
    builder.append("_").append(
        JobId.jobIdFormat.get().format(
            getTaskId().getJobId().getAppId().getId()));
    builder.append("_");
    builder.append(taskId.getTaskType() == TaskType.MAP ? "m" : "r");
    builder.append("_")
        .append(TaskId.taskIdFormat.get().format(taskId.getId()));
    builder.append("_");
    builder.append(getId());
    return builder.toString();
  }

  @Override
  public int compareTo(TaskAttemptId other) {
    int taskIdComp = this.getTaskId().compareTo(other.getTaskId());
    if (taskIdComp == 0) {
      return this.getId() - other.getId();
    } else {
      return taskIdComp;
    }
  }
}