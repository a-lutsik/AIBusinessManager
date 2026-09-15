package com.aibusinessmanager.platform.outbox;

import org.jooq.Record;

public interface OutboxHandler {

    String type();

    void handle(Record event);
}
