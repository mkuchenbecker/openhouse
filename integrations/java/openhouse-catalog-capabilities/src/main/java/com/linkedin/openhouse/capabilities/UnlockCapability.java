package com.linkedin.openhouse.capabilities;

/** The {@link CapabilityNames#UNLOCK} capability: {@code ALTER TABLE <t> UNLOCK [REASON <r>]}. */
public interface UnlockCapability {

  /**
   * Removes the table's lock without loading the table (loading fails on a {@code SYSTEM_ONLY}
   * lock). The implementation enforces authorization before the change.
   *
   * @param target the validated database and table
   * @param reason the lock to remove; the SQL default for an omitted reason is {@link
   *     UnlockReason#LEGACY}
   */
  void unlockTable(UnlockTableTarget target, UnlockReason reason);
}
