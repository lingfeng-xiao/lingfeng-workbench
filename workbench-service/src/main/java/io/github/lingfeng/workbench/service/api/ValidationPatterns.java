package io.github.lingfeng.workbench.service.api;

public final class ValidationPatterns {
  public static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$";
  public static final String WORK_ITEM_ID = "^wi_[A-Za-z0-9]+$";
  public static final String MISSION_ID = "^mi_[A-Za-z0-9]+$";
  public static final String RUN_ID = "^run_[A-Za-z0-9]+$";
  public static final String INTERACTION_ID = "^int_[A-Za-z0-9]+$";
  public static final String NOTIFICATION_ID = "^ntf_[A-Za-z0-9]+$";
  public static final String TASK_ID = "^task_[A-Za-z0-9]+$";
  public static final String DIGEST = "^[a-f0-9]{64}$";

  private ValidationPatterns() {}
}
