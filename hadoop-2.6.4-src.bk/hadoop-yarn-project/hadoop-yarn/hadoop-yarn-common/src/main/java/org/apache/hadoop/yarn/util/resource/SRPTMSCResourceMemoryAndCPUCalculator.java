package org.apache.hadoop.yarn.util.resource;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.Resource;

@Private
@Unstable
public class SRPTMSCResourceMemoryAndCPUCalculator extends ResourceCalculator {
  
  @Override
  public int compare(Resource clusterResource, Resource lhs, Resource rhs) {
    
    if (lhs.equals(rhs)) {
      return 0;
    }
    
    if (isInvalidDivisor(clusterResource)) {
      if ((lhs.getMemory() < rhs.getMemory() && lhs.getVirtualCores() > rhs
          .getVirtualCores())
          || (lhs.getMemory() > rhs.getMemory() && lhs.getVirtualCores() < rhs
              .getVirtualCores())) {
        return 0;
      } else if (lhs.getMemory() > rhs.getMemory()
          || lhs.getVirtualCores() > rhs.getVirtualCores()) {
        return 1;
      } else if (lhs.getMemory() < rhs.getMemory()
          || lhs.getVirtualCores() < rhs.getVirtualCores()) {
        return -1;
      }
    }

    float l = getResourceAsValue(clusterResource, lhs, true);
    float r = getResourceAsValue(clusterResource, rhs, true);
    
    if (l < r) {
      return -1;
    } else if (l > r) {
      return 1;
    } else {
      l = getResourceAsValue(clusterResource, lhs, false);
      r = getResourceAsValue(clusterResource, rhs, false);
      if (l < r) {
        return -1;
      } else if (l > r) {
        return 1;
      }
    }
    
    return 0;
  }

  /**
   * Use 'dominant' for now since we only have 2 resources - gives us a slight
   * performance boost.
   * 
   * Once we add more resources, we'll need a more complicated (and slightly
   * less performant algorithm).
   */
  protected float getResourceAsValue(
      Resource clusterResource, Resource resource, boolean dominant) {
    // Just use 'dominant' resource
    return (dominant) ?
        Math.max(
            (float)resource.getMemory() / clusterResource.getMemory(), 
            (float)resource.getVirtualCores() / clusterResource.getVirtualCores()
            ) 
        :
          Math.min(
              (float)resource.getMemory() / clusterResource.getMemory(), 
              (float)resource.getVirtualCores() / clusterResource.getVirtualCores()
              ); 
  }
  
  @Override
  public int computeAvailableContainers(Resource available, Resource required) {
    return Math.min(
        available.getMemory() / required.getMemory(), 
        available.getVirtualCores() / required.getVirtualCores());
  }

  @Override
  public float divide(Resource clusterResource, 
      Resource numerator, Resource denominator) {
    return 
        getResourceAsValue(clusterResource, numerator, true) / 
        getResourceAsValue(clusterResource, denominator, true);
  }
  
  @Override
  public boolean isInvalidDivisor(Resource r) {
    if (r.getMemory() == 0.0f || r.getVirtualCores() == 0.0f) {
      return true;
    }
    return false;
  }

  @Override
  public float ratio(Resource a, Resource b) {
    return Math.max(
        (float)a.getMemory()/b.getMemory(), 
        (float)a.getVirtualCores()/b.getVirtualCores()
        );
  }

  @Override
  public Resource divideAndCeil(Resource numerator, int denominator) {
    return Resources.createResource(
        divideAndCeil(numerator.getMemory(), denominator),
        divideAndCeil(numerator.getVirtualCores(), denominator)
        );
  }

  @Override
  public Resource normalize(Resource r, Resource minimumResource,
                            Resource maximumResource, Resource stepFactor) {
    int normalizedMemory = Math.min(
      roundUp(
        Math.max(r.getMemory(), minimumResource.getMemory()),
        stepFactor.getMemory()),
      maximumResource.getMemory());
    int normalizedCores = Math.min(
      roundUp(
        Math.max(r.getVirtualCores(), minimumResource.getVirtualCores()),
        stepFactor.getVirtualCores()),
      maximumResource.getVirtualCores());
    return Resources.createResource(normalizedMemory,
      normalizedCores);
  }

  @Override
  public Resource roundUp(Resource r, Resource stepFactor) {
    return Resources.createResource(
        roundUp(r.getMemory(), stepFactor.getMemory()), 
        roundUp(r.getVirtualCores(), stepFactor.getVirtualCores())
        );
  }

  @Override
  public Resource roundDown(Resource r, Resource stepFactor) {
    return Resources.createResource(
        roundDown(r.getMemory(), stepFactor.getMemory()),
        roundDown(r.getVirtualCores(), stepFactor.getVirtualCores())
        );
  }

  @Override
  public Resource multiplyAndNormalizeUp(Resource r, double by,
      Resource stepFactor) {
    return Resources.createResource(
        roundUp(
            (int)Math.ceil(r.getMemory() * by), stepFactor.getMemory()),
        roundUp(
            (int)Math.ceil(r.getVirtualCores() * by), 
            stepFactor.getVirtualCores())
        );
  }

  @Override
  public Resource multiplyAndNormalizeDown(Resource r, double by,
      Resource stepFactor) {
    return Resources.createResource(
        roundDown(
            (int)(r.getMemory() * by), 
            stepFactor.getMemory()
            ),
        roundDown(
            (int)(r.getVirtualCores() * by), 
            stepFactor.getVirtualCores()
            )
        );
  }
  
}
