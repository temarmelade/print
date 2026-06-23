package com.printkiosk.server.exception;

import java.util.UUID;


public class JobNotFoundException extends RuntimeException {
  public JobNotFoundException(UUID id) { super("Job not found: " + id); }
}
