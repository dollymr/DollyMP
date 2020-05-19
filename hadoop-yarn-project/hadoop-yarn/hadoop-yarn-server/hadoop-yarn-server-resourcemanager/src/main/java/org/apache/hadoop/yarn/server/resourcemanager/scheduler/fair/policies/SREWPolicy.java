package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.policies;

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.server.resourcemanager.resource.ResourceType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.Schedulable;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.SchedulingPolicy;
import org.apache.hadoop.yarn.util.resource.DefaultResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;


public class SREWPolicy extends SchedulingPolicy {
	
	private static final Log LOG = LogFactory.getLog(
			SREWPolicy.class.getName());
	public static final String NAME = "SRPT";
	private WorkloadComparator comparator = new WorkloadComparator();
	private static final DefaultResourceCalculator RESOURCE_CALCULATOR = new DefaultResourceCalculator();

	/**
	 * remaining workload comparator for the schedulable
	 * 
	 * @author Zhibo Yang at July 2016
	 */
	private static class WorkloadComparator implements Comparator<Schedulable>, Serializable {

		private static final long serialVersionUID = 1L;
		private static final float T = 0.001f;

		@Override
		public int compare(Schedulable s1, Schedulable s2) {
			// s1 < s2 returns negative number;
			// s1 > s2 returns positive number;
			// s1 = s2 returns 0
			float workload1 = s1.getEffectiveWorkload();
			float workload2 = s2.getEffectiveWorkload();
			int res = 0;
			if (LOG.isDebugEnabled()) {
				LOG.debug("workloads of s1:" + s1.getName() + " and s2: "+ 
						s2.getName() + " are: " + workload1 + ", " + workload2);
			}
			if (Math.abs(workload1 - workload2) > T)
				if (workload1 > workload2)
					res = 1;
				else if (workload1 < workload2)
					res = -1;
			if (res == 0) {
				// Apps are tied in fairness ratio. Break the tie by submit time
				// and job
				// name to get a deterministic ordering, which is useful for
				// unit tests.
				res = (int) Math.signum(s1.getStartTime() - s2.getStartTime());
				if (res == 0)
					res = s1.getName().compareTo(s2.getName());
			}
			return res;
		}
	}

	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return NAME;
	}

	@Override
	public Comparator<Schedulable> getComparator() {
		return comparator;
	}

	@Override
	public Resource getHeadroom(Resource queueFairShare, Resource queueUsage, Resource clusterAvailable) {
		int queueAvailableMemory = Math.max(queueFairShare.getMemory() - queueUsage.getMemory(), 0);
		Resource headroom = Resources.createResource(Math.min(clusterAvailable.getMemory(), queueAvailableMemory),
				clusterAvailable.getVirtualCores());
		return headroom;
	}

	@Override
	public void computeShares(Collection<? extends Schedulable> schedulables, Resource totalResources) {
		ComputeFairShares.computeShares(schedulables, totalResources, ResourceType.MEMORY);
	}

	@Override
	public void computeSteadyShares(Collection<? extends FSQueue> queues, Resource totalResources) {
		ComputeFairShares.computeSteadyShares(queues, totalResources, ResourceType.MEMORY);
	}

	@Override
	public boolean checkIfUsageOverFairShare(Resource usage, Resource fairShare) {
		return Resources.greaterThan(RESOURCE_CALCULATOR, null, usage, fairShare);
	}

	@Override
	public boolean checkIfAMResourceUsageOverLimit(Resource usage, Resource maxAMResource) {
		return usage.getMemory() > maxAMResource.getMemory();
	}

	@Override
	public byte getApplicableDepth() {
		return SchedulingPolicy.DEPTH_ANY;
	}

}
