package com.swiftbureau.screening;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {
}
