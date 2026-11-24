package com.linkedin.openhouse.tables.dto.mapper.iceberg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Replication;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ReplicationConfig;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Retention;
import com.linkedin.openhouse.tables.common.DefaultColumnPattern;
import com.linkedin.openhouse.tables.common.ReplicationInterval;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.utils.IntervalToCronConverter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public class PoliciesSpecMapper {

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
    return updatedPolicies;
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

  /**
   * The stored {@code policies} text with the tags of each column in the declaration order of
   * {@link PolicyTag.Tag}, the order this mapper writes them in ({@link PolicyTag}), and nothing
   * else changed. Text written before the order was fixed holds them in whatever order its service
   * process had (work item N8); comparing a request's policies with this, too, keeps the same tags
   * in another order from counting as a change. Returns {@code stored} itself when anything other
   * than the tag order would differ, that is when the text is not exactly what this mapper writes
   * for its own parse, or cannot be parsed.
   *
   * @param stored the stored {@code policies} text
   * @return the same text with the tags in declaration order, or {@code stored}
   */
  @Named("withTagsInDeclarationOrder")
  public String withTagsInDeclarationOrder(String stored) {
    JsonElement tree;
    try {
      tree = JsonParser.parseString(stored);
    } catch (JsonParseException e) {
      return stored;
    }
    if (!tree.isJsonObject() || !gson.toJson(tree).equals(stored)) {
      return stored;
    }
    JsonElement columnTags = tree.getAsJsonObject().get("columnTags");
    if (columnTags == null || !columnTags.isJsonObject()) {
      return stored;
    }
    for (Map.Entry<String, JsonElement> column : columnTags.getAsJsonObject().entrySet()) {
      if (!column.getValue().isJsonObject()) {
        continue;
      }
      JsonObject policyTag = column.getValue().getAsJsonObject();
      JsonElement tags = policyTag.get("tags");
      if (tags == null || !tags.isJsonArray()) {
        continue;
      }
      List<JsonElement> sorted = new ArrayList<>();
      tags.getAsJsonArray().forEach(sorted::add);
      sorted.sort(Comparator.comparingInt(PoliciesSpecMapper::declarationIndex));
      JsonArray inOrder = new JsonArray();
      sorted.forEach(inOrder::add);
      policyTag.add("tags", inOrder);
    }
    return gson.toJson(tree);
  }

  /** A tag's place in the declaration order; anything that is not a tag's name comes last. */
  private static int declarationIndex(JsonElement tag) {
    if (tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()) {
      for (PolicyTag.Tag known : PolicyTag.Tag.values()) {
        if (known.name().equals(tag.getAsString())) {
          return known.ordinal();
        }
      }
    }
    return PolicyTag.Tag.values().length;
  }

  private static Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
}
