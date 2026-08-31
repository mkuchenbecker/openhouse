package com.linkedin.openhouse.common.api.validator;

public final class ValidatorConstants {

  private ValidatorConstants() {}

  /**
   * The identifier charset itself, without anchors. Every constant below is composed from it rather
   * than restating it, so widening what an identifier may contain is one edit rather than a hunt
   * for the copies that drifted.
   */
  private static final String IDENTIFIER_LEVEL = "[a-zA-Z0-9_]+";

  public static final String ALPHA_NUM_UNDERSCORE_PATTERN_SEARCH_REGEX =
      "^%?" + IDENTIFIER_LEVEL + "%?$";

  public static final String ALPHA_NUM_UNDERSCORE_PATTERN_SEARCH_ERROR_MSG =
      "Only alphanumerics and underscore supported. The wildcard '%' can only be at the beginning or end of the string";

  public static final String ALPHA_NUM_UNDERSCORE_REGEX = "^" + IDENTIFIER_LEVEL + "$";
  public static final String ALPHA_NUM_UNDERSCORE_ERROR_MSG =
      "Only alphanumerics and underscore supported";

  /**
   * The charset of an encoded namespace identifier: {@link #ALPHA_NUM_UNDERSCORE_REGEX} levels
   * joined by the {@code .} that {@code NamespaceUtil.encode} writes between them.
   *
   * <p>Deliberately a separate constant from {@link #ALPHA_NUM_UNDERSCORE_REGEX} rather than a
   * widening of it. Table ids are held to the narrower charset so that {@code db.table.history} has
   * exactly one reading; widening the shared constant would have taken that away as a side effect.
   *
   * <p>At the shipped namespace depth of 1 an encoded namespace contains no separator, so this
   * accepts and rejects exactly what {@link #ALPHA_NUM_UNDERSCORE_REGEX} does.
   */
  public static final String NAMESPACE_ID_REGEX =
      "^" + IDENTIFIER_LEVEL + "(\\." + IDENTIFIER_LEVEL + ")*$";

  public static final String NAMESPACE_ID_ERROR_MSG =
      "Only alphanumerics and underscore supported, with '.' separating namespace levels";

  public static final String ALPHA_NUM_UNDERSCORE_REGEX_HYPHEN_ALLOW = "^[a-zA-Z0-9-_]+$";
  // supported memory format: Integer values ending with G or M
  public static final String ALPHA_NUM_UNDERSCORE_ERROR_MSG_HYPHEN_ALLOW =
      "Only alphanumerics, hyphen and underscore supported";
  public static final int MAX_ALLOWED_CLUSTERING_COLUMNS = 4;
  public static final String INITIAL_TABLE_VERSION = "INITIAL_VERSION";
}
