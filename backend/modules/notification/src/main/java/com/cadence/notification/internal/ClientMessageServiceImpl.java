package com.cadence.notification.internal;

import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.VisitMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ClientMessageServiceImpl implements ClientMessageService {

    private static final Logger log = LoggerFactory.getLogger(ClientMessageServiceImpl.class);

    private final DispatchQueue queue;
    private final CrmService crm;

    ClientMessageServiceImpl(DispatchQueue queue, CrmService crm) {
        this.queue = queue;
        this.crm = crm;
    }

    @Override
    @Transactional
    public boolean enqueue(VisitMessage message) {
        if (!message.manualFallback() && crm.findActiveById(message.clientId()).filter(c -> c.telegramChatId() != null).isEmpty()) {
            log.debug("Visit message not queued, nobody to deliver it to: visit={}, kind={}", message.appointmentId(), message.kind());
            return false;
        }
        boolean queued = queue.enqueue(message);
        log.debug("Visit message {}: visit={}, kind={}, start={}", queued ? "queued" : "already queued or sent",
                message.appointmentId(), message.kind(), message.serviceStart());
        return queued;
    }
}
