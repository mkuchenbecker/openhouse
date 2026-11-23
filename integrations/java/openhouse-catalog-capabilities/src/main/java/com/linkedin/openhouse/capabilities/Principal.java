package com.linkedin.openhouse.capabilities;

/**
 * The SQL spelling of principals: the keyword {@code PUBLIC}, in any case, is the global user
 * group, which access policies store as {@code *}. Every other principal is passed through
 * unchanged.
 */
public final class Principal {

  /** The SQL keyword for the global user group. */
  public static final String GLOBAL_USER_GROUP = "PUBLIC";

  /** The access-policy spelling of the global user group. */
  public static final String GLOBAL_USER_GROUP_ACL = "*";

  private Principal() {}

  /**
   * The access-policy spelling of a principal written in SQL.
   *
   * <p>The case comparison uses the default locale, as the SQL extensions always have.
   */
  public static String toAcl(String principal) {
    return GLOBAL_USER_GROUP.equals(principal.toUpperCase()) ? GLOBAL_USER_GROUP_ACL : principal;
  }

  /** The SQL spelling of a principal as access policies store it. */
  public static String fromAcl(String principalAcl) {
    return GLOBAL_USER_GROUP_ACL.equals(principalAcl) ? GLOBAL_USER_GROUP : principalAcl;
  }
}
