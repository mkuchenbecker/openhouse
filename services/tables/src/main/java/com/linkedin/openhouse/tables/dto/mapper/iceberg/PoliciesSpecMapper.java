package com.linkedin.openhouse.tables.dto.mapper.iceberg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PerformanceTier;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PerformanceTierConfig;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Replication;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ReplicationConfig;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Retention;
import com.linkedin.openhouse.tables.common.DefaultColumnPattern;
import com.linkedin.openhouse.tables.common.ReplicationInterval;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.utils.IntervalToCronConverter;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public class PoliciesSpecMapper {

  @Autowired ClusterProperties clusterProperties;

  /**
   * Given an Iceberg {@link TableDto}, serialize to JsonString format.
   *
   * @param tableDto an iceberg table
   * @return String if Policies object is set in TableDto otherwise ""
   */
  @Named("toPoliciesJsonString")
  public String toPoliciesJsonString(TableDto tableDto) throws JsonParseException {
    if (tableDto.getPolicies() != null) {
      try {
        return gson.toJson(tableDto.getPolicies());
      } catch (JsonParseException e) {
        throw new JsonParseException("Malformed policies json");
      }
    }
    return "";
  }

  /**
   * @param policiesString an openhouse table policies
   * @return Policies {@link Policies} Null if houseTable has no policies set
   */
  @Named("toPoliciesObject")
  public Policies toPoliciesObject(String policiesString) throws JsonParseException {
    if (!policiesString.isEmpty()) {
      try {
        return gson.fromJson(policiesString, Policies.class);
      } catch (JsonParseException e) {
        throw new JsonParseException(
            "Internal server error. Cannot convert policies Object to json");
      }
    }
    return null;
  }

  /**
   * mapPolicies is a mapStruct function which assigns default pattern in retention config if the
   * pattern is empty. Default values for pattern are defined at {@link DefaultColumnPattern} based
   * on granularity.
   *
   * @param policies for Openhouse table
   * @return mapped policies object
   */
  @Named("mapPolicies")
  public Policies mapPolicies(Policies policies) {
    String defaultPattern;
    Policies updatedPolicies = policies;
    if (policies != null
        && policies.getRetention() != null
        && policies.getRetention().getColumnPattern() != null
        && policies.getRetention().getColumnPattern().getPattern().isEmpty()) {
      if (policies
          .getRetention()
          .getGranularity()
          .name()
          .equals(DefaultColumnPattern.HOUR.toString())) {
        defaultPattern = DefaultColumnPattern.HOUR.getPattern();
      } else {
        defaultPattern = DefaultColumnPattern.DAY.getPattern();
      }
      Retention retentionPolicy =
          policies
              .getRetention()
              .toBuilder()
              .columnPattern(
                  policies
                      .getRetention()
                      .getColumnPattern()
                      .toBuilder()
                      .pattern(defaultPattern)
                      .build())
              .build();
      updatedPolicies = policies.toBuilder().retention(retentionPolicy).build();
    }
    if (policies != null && policies.getReplication() != null) {
      updatedPolicies =
          updatedPolicies
              .toBuilder()
              .replication(mapReplicationPolicies(policies.getReplication()))
              .build();
    }
    if (policies != null) {
      updatedPolicies =
          updatedPolicies
              .toBuilder()
              .performanceTier(resolvePerformanceTier(policies.getPerformanceTier()))
              .build();
    }
    return updatedPolicies;
  }

  /**
   * Resolves the performance tier config by filling in defaults when the field is absent or when
   * auto=true and no resolved tier has been set yet.
   *
   * <p>Rules:
   *
   * <ul>
   *   <li>null input → {auto: true, resolved: &lt;cluster default&gt;}
   *   <li>auto=true, resolved=null → {auto: true, resolved: &lt;cluster default&gt;}
   *   <li>auto=true, resolved=non-null → keep as-is (autotuner already set it)
   *   <li>auto=false, resolved=non-null → keep as-is (customer pinned it)
   * </ul>
   */
  PerformanceTierConfig resolvePerformanceTier(PerformanceTierConfig input) {
    PerformanceTier clusterDefault =
        PerformanceTier.valueOf(clusterProperties.getClusterPerformanceTierDefault());
    if (input == null) {
      return PerformanceTierConfig.builder().auto(true).resolved(clusterDefault).build();
    }
    if (input.getResolved() == null) {
      return input.toBuilder().resolved(clusterDefault).build();
    }
    return input;
  }

  /**
   * Returns the HDFS replication factor for the given tier as configured in this cluster.
   *
   * @param tier the performance tier
   * @return HDFS block replication factor
   */
  public short getHdfsReplicationFactor(PerformanceTier tier) {
    switch (tier) {
      case HIGH:
        return (short) clusterProperties.getClusterPerformanceTierReplicationHigh();
      case MAX:
        return (short) clusterProperties.getClusterPerformanceTierReplicationMax();
      case STANDARD:
      default:
        return (short) clusterProperties.getClusterPerformanceTierReplicationStandard();
    }
  }

  /**
   * mapRetentionPolicies is a mapStruct function which assigns default interval value in
   * replication config if the interval is empty and the generated cron schedule from the interval
   * value. Default values for pattern are defined at {@link ReplicationInterval}.
   *
   * @param replicationPolicy config for Openhouse table
   * @return mapped policies object
   */
  private Replication mapReplicationPolicies(Replication replicationPolicy) {
    if (replicationPolicy != null && replicationPolicy.getConfig() != null) {
      List<ReplicationConfig> replicationConfig =
          replicationPolicy.getConfig().stream()
              .map(
                  replication -> {
                    if (replication == null || StringUtils.isEmpty(replication.getDestination())) {
                      return null;
                    }
                    String destination = replication.getDestination().toUpperCase();
                    String interval = replication.getInterval();
                    String cronSchedule = replication.getCronSchedule();

                    if (StringUtils.isEmpty(interval)) {
                      interval = ReplicationInterval.DEFAULT.getInterval();
                    }
                    if (StringUtils.isEmpty(cronSchedule)) {
                      cronSchedule = IntervalToCronConverter.generateCronExpression(interval);
                    }
                    return replication
                        .toBuilder()
                        .destination(destination)
                        .interval(interval)
                        .cronSchedule(cronSchedule)
                        .build();
                  })
              .collect(Collectors.toList());
      return replicationPolicy.toBuilder().config(replicationConfig).build();
    }
    return replicationPolicy;
  }

  private static Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
}
