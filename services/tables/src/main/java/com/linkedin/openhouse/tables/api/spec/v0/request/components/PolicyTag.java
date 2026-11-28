package com.linkedin.openhouse.tables.api.spec.v0.request.components;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * The policy tags of a column.
 *
 * <p>The tags are a set: two lists of the same tags in different orders are the same tags ({@link
 * #equals} compares them as sets). Wherever they are written out, in the stored {@code policies}
 * table property (Gson) and in API responses (Jackson), they are written in the declaration order
 * of {@link Tag} ({@code PII} before {@code HC}), so every service process writes the same text. A
 * {@code HashSet}'s order would follow the enum constants' identity hash codes and so differ from
 * one service process to the next.
 */
@Builder
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyTag {
  @Schema(description = "Policy tags", example = "PII, HC")
  @JsonAdapter(InDeclarationOrder.class)
  Set<Tag> tags;

  public enum Tag {
    PII,
    HC
  }

  /**
   * The tags in the declaration order of {@link Tag}; a tag Gson could not read (an unknown name
   * reads as {@code null}) comes last. Jackson writes the tags from this getter.
   */
  public Set<Tag> getTags() {
    return inDeclarationOrder(tags);
  }

  private static final Comparator<Tag> DECLARATION_ORDER =
      Comparator.nullsLast(Comparator.naturalOrder());

  static Set<Tag> inDeclarationOrder(Set<Tag> tags) {
    if (tags == null) {
      return null;
    }
    List<Tag> sorted = new ArrayList<>(tags);
    sorted.sort(DECLARATION_ORDER);
    return new LinkedHashSet<>(sorted);
  }

  /**
   * Gson's reading and writing of {@link #tags}: read as Gson reads a set (a {@code LinkedHashSet},
   * each element by Gson's own enum adapter, so an unknown name is still {@code null}), written in
   * declaration order.
   */
  static final class InDeclarationOrder implements TypeAdapterFactory {
    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      TypeAdapter<Tag> element = gson.getAdapter(Tag.class);
      return (TypeAdapter<T>)
          new TypeAdapter<Set<Tag>>() {
            @Override
            public void write(JsonWriter out, Set<Tag> value) throws IOException {
              if (value == null) {
                out.nullValue();
                return;
              }
              out.beginArray();
              for (Tag tag : inDeclarationOrder(value)) {
                element.write(out, tag);
              }
              out.endArray();
            }

            @Override
            public Set<Tag> read(JsonReader in) throws IOException {
              if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
              }
              Set<Tag> read = new LinkedHashSet<>();
              in.beginArray();
              while (in.hasNext()) {
                read.add(element.read(in));
              }
              in.endArray();
              return inDeclarationOrder(read);
            }
          };
    }
  }
}
