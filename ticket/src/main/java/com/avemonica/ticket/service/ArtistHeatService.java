package com.avemonica.ticket.service;

public interface ArtistHeatService {

    void markArtistDirty(Long artistId);

    void markEventDirty(Long eventId);

    void refreshDirtyHeat();

    void refreshArtistHeat(Long artistId);

    void refreshAllArtistHeat();
}