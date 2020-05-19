package org.apache.hadoop.yarn.util.resource;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.Resource;

@Private
@Unstable
public class SRPTMSCResourceMemoryOnlyCalculator extends ResourceCalculator {
	
  @Override
  public int compare(Resource unused, Resource lhs, Resource rhs) {
    // Only consider memory
    return lhs.getMemory() - rhs.getMemory();
  }

  @Override
  public int computeAvailableContainers(Resource available, Resource required) {
    // Only consider memory
    return available.getMemory() / required.getMemory();
  }
  
  @Override
  public float divide(Resource unused, 
      Resource numerator, Resource denominator) {
    return ratio(numerator, denominator);
  }
  
  public boolean isInvalidDivisor(Resource r) {
    if (r.getMemory() == 0.0f) {
      return true;
    }
    return false;
  }
  
  @Override
  public float ratio(Resource a, Resource b) {
    return (float)a.getMemory() / b.getMemory();
  }

  @Override
  public Resource divideAndCeil(Resource numerator, int denominator) {
    return Resources.createResource(
        divideAndCeil(numerator.getMemory(), denominator));
  }

  @Override
  public Resource normalize(Resource r, Resource minimumResource,
      Resource maximumResource, Resource stepFactor) {
    int normalizedMemory = Math.min(
        roundUp(
            Math.max(r.getMemory(), minimumResource.getMemory()),
            stepFactor.getMemory()),
            maximumResource.getMemory());
    return Resources.createResource(normalizedMemory);
  }

  @Override
  public Resource normalize(Resource r, Resource minimumResource,
                            Resource maximumResource) {
    return normalize(r, minimumResource, maximumResource, minimumResource);
  }

  @Override
  public Resource roundUp(Resource r, Resource stepFactor) {
    return Resources.createResource(
        roundUp(r.getMemory(), stepFactor.getMemory())
        );
  }

  @Override
  public Resource roundDown(Resource r, Resource stepFactor) {
    return Resources.createResource(
        roundDown(r.getMemory(), stepFactor.getMemory()));
  }

  @Override
  public Resource multiplyAndNormalizeUp(Resource r, double by,
      Resource stepFactor) {
    return Resources.createResource(
        roundUp((int)(r.getMemory() * by + 0.5), stepFactor.getMemory())
        );
  }

  @Override
  public Resource multiplyAndNormalizeDown(Resource r, double by,
      Resource stepFactor) {
    return Resources.createResource(
        roundDown(
            (int)(r.getMemory() * by), 
            stepFactor.getMemory()
            )
        );
  }
  
}
