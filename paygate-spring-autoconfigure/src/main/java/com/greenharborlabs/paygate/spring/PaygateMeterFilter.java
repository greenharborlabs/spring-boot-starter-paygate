package com.greenharborlabs.paygate.spring;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MeterFilter} that caps the cardinality of the {@code endpoint} tag on L402 meters. When
 * the configured maximum is reached, new endpoint values are replaced with the overflow sentinel
 * (default: {@code "_other"}).
 */
public class PaygateMeterFilter implements MeterFilter {

  private static final String ENDPOINT_TAG = "endpoint";
  private static final String L402_PREFIX = "paygate.";

  private final int maxEndpointCardinality;
  private final String overflowTagValue;
  private final Set<String> observedEndpoints = ConcurrentHashMap.newKeySet();

  public PaygateMeterFilter(int maxEndpointCardinality, String overflowTagValue) {
    this.maxEndpointCardinality = maxEndpointCardinality;
    this.overflowTagValue = overflowTagValue;
  }

  @Override
  public Meter.Id map(Meter.Id id) {
    if (!id.getName().startsWith(L402_PREFIX)) {
      return id;
    }

    String endpointValue = id.getTag(ENDPOINT_TAG);
    if (endpointValue == null) {
      return id;
    }

    // Already tracked — pass through
    if (observedEndpoints.contains(endpointValue)) {
      return id;
    }

    // Try to add; allow a small overshoot rather than using locks
    if (observedEndpoints.size() < maxEndpointCardinality) {
      observedEndpoints.add(endpointValue);
      return id;
    }

    // Cap exceeded — replace with overflow sentinel
    return replaceEndpointTag(id, overflowTagValue);
  }

  private Meter.Id replaceEndpointTag(Meter.Id id, String newValue) {
    var newTags =
        id.getTags().stream()
            .map(tag -> tag.getKey().equals(ENDPOINT_TAG) ? Tag.of(ENDPOINT_TAG, newValue) : tag)
            .toList();
    return id.withTags(newTags);
  }
}
