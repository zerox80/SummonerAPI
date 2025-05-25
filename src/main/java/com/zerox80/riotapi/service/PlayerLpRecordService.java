package com.zerox80.riotapi.service;

import com.zerox80.riotapi.model.LeagueEntryDTO;
import com.zerox80.riotapi.model.PlayerLpRecord;
import com.zerox80.riotapi.repository.PlayerLpRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class PlayerLpRecordService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerLpRecordService.class);
    private final PlayerLpRecordRepository playerLpRecordRepository;

    @Autowired
    public PlayerLpRecordService(PlayerLpRecordRepository playerLpRecordRepository) {
        this.playerLpRecordRepository = playerLpRecordRepository;
    }

    @Transactional
    public CompletableFuture<Void> savePlayerLpRecordsAsync(String puuid, List<LeagueEntryDTO> leagueEntries) {

        return CompletableFuture.runAsync(() -> {
            Instant now = Instant.now();
            for (LeagueEntryDTO entry : leagueEntries) {
                if ("RANKED_SOLO_5x5".equals(entry.getQueueType()) || "RANKED_FLEX_SR".equals(entry.getQueueType())) {
                    PlayerLpRecord record = new PlayerLpRecord(
                            puuid,
                            entry.getQueueType(),
                            now,
                            entry.getLeaguePoints(),
                            entry.getTier(),
                            entry.getRank()
                    );
                    try {
                        playerLpRecordRepository.save(record);
                        logger.debug("Saved LP record for puuid {}, queue {}: {} LP at {}", puuid, entry.getQueueType(), entry.getLeaguePoints(), now);
                    } catch (Exception e) {
                        logger.error("Failed to save LP record for puuid {} (queue: {}): {}. This might lead to a transaction rollback.", puuid, entry.getQueueType(), e.getMessage(), e);

                    }
                }
            }
        });
    }
} 
