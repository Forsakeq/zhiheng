package com.yqsoft.persistence;

import java.util.Map;
import java.util.Set;

public interface SyncProjectionProvider {

    Set<String> entityTypes();

    Map<String, Object> project(String entityType, long entityId);
}
