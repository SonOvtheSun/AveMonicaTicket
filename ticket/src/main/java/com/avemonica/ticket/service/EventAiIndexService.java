package com.avemonica.ticket.service;

public interface EventAiIndexService {

    void rebuildEventAiIndex(Long eventId);

    void rebuildEventAiIndex(Long eventId, boolean force);

    void deleteEventAiIndex(Long eventId);
}