package com.cadence.notification.internal;

import com.cadence.notification.api.OwnerTaskService;
import com.cadence.notification.api.OwnerTaskView;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.OWNER_TASK;

@Service
public class OwnerTaskServiceImpl implements OwnerTaskService {

    private final DSLContext dsl;

    public OwnerTaskServiceImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public OwnerTaskView create(
            String kind,
            String title,
            String body,
            String copyText,
            String link,
            UUID appointmentId,
            UUID clientId
    ) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        dsl.insertInto(OWNER_TASK)
                .set(OWNER_TASK.ID, id)
                .set(OWNER_TASK.TENANT_ID, TenantContext.require())
                .set(OWNER_TASK.KIND, kind)
                .set(OWNER_TASK.TITLE, title)
                .set(OWNER_TASK.BODY, body)
                .set(OWNER_TASK.COPY_TEXT, copyText)
                .set(OWNER_TASK.LINK, link)
                .set(OWNER_TASK.APPOINTMENT_ID, appointmentId)
                .set(OWNER_TASK.CLIENT_ID, clientId)
                .set(OWNER_TASK.CREATED_AT, Utc.toLocal(now))
                .execute();
        return new OwnerTaskView(id, kind, title, body, copyText, link, appointmentId, now, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnerTaskView> listOpen() {
        return dsl.selectFrom(OWNER_TASK)
                .where(TenantAwareDsl.tenantEquals(OWNER_TASK.TENANT_ID).and(OWNER_TASK.COMPLETED_AT.isNull()))
                .orderBy(OWNER_TASK.CREATED_AT.desc())
                .fetch(r -> new OwnerTaskView(
                        r.get(OWNER_TASK.ID),
                        r.get(OWNER_TASK.KIND),
                        r.get(OWNER_TASK.TITLE),
                        r.get(OWNER_TASK.BODY),
                        r.get(OWNER_TASK.COPY_TEXT),
                        r.get(OWNER_TASK.LINK),
                        r.get(OWNER_TASK.APPOINTMENT_ID),
                        Utc.toInstant(r.get(OWNER_TASK.CREATED_AT)),
                        r.get(OWNER_TASK.COMPLETED_AT) == null ? null : Utc.toInstant(r.get(OWNER_TASK.COMPLETED_AT))
                ));
    }

    @Override
    @Transactional
    public void complete(UUID taskId) {
        dsl.update(OWNER_TASK)
                .set(OWNER_TASK.COMPLETED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(OWNER_TASK.TENANT_ID).and(OWNER_TASK.ID.eq(taskId)))
                .execute();
    }
}
