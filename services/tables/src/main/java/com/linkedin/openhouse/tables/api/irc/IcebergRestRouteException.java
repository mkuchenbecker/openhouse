package com.linkedin.openhouse.tables.api.irc;

import lombok.Getter;

/**
 * A request under the IRC base path that this server does not serve: either a route of the IRC
 * specification that is not implemented here yet ({@link #isSpecRoute()}), or a path that is not an
 * IRC route at all.
 */
@Getter
public class IcebergRestRouteException extends RuntimeException {
  private final boolean specRoute;

  public IcebergRestRouteException(String message, boolean specRoute) {
    super(message);
    this.specRoute = specRoute;
  }
}
