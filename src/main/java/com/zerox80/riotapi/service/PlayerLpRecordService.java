package com.zerox80.riotapi.service;

import com.zerox80.riotapi.model.LeagueEntryDTO;
import com.zerox80.riotapi.model.MatchV5Dto;
import com.zerox80.riotapi.model.PlayerLpRecord;
import com.zerox80.riotapi.model.Summoner;
import com.zerox80.riotapi.repository.PlayerLpRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    public void savePlayerLpRecords(String puuid, List<LeagueEntryDTO> leagueEntries, Instant timestamp) {
        Instant ts = timestamp != null ? timestamp : Instant.now();
        for (LeagueEntryDTO entry : leagueEntries) {
            if ("RANKED_SOLO_5x5".equals(entry.getQueueType()) || "RANKED_FLEX_SR".equals(entry.getQueueType())) {
                PlayerLpRecord record = new PlayerLpRecord(
                        puuid,
                        entry.getQueueType(),
                        ts,
                        entry.getLeaguePoints(),
                        entry.getTier(),
                        entry.getRank()
                );
                playerLpRecordRepository.save(record);
                logger.debug("Saved LP record for puuid {}, queue {}: {} LP at {}", puuid, entry.getQueueType(), entry.getLeaguePoints(), ts);
            }
        }
    }

    public void calculateAndSetLpChangesForMatches(Summoner summoner, List<MatchV5Dto> matchHistory) {
        if (summoner == null || !StringUtils.hasText(summoner.getPuuid()) || matchHistory == null || matchHistory.isEmpty()) {
            return;
        }

        for (MatchV5Dto match : matchHistory) {
            if (match.getInfo() == null) continue;

            int queueId = match.getInfo().getQueueId();
            String queueTypeForDbQuery;
            if (queueId == 420) {
                queueTypeForDbQuery = "RANKED_SOLO_5x5";
            } else if (queueId == 440) {
                queueTypeForDbQuery = "RANKED_FLEX_SR";
            } else {
                continue;
            }

            Instant matchEndTime = Instant.ofEpochMilli(match.getInfo().getGameEndTimestamp());

            try {
                Optional<PlayerLpRecord> recordBeforeOpt = playerLpRecordRepository
                        .findFirstByPuuidAndQueueTypeAndTimestampBeforeOrderByTimestampDesc(
                                summoner.getPuuid(), queueTypeForDbQuery, matchEndTime);

                Optional<PlayerLpRecord> recordAfterOpt = playerLpRecordRepository
                        .findFirstByPuuidAndQueueTypeAndTimestampGreaterThanEqualOrderByTimestampAsc(
                                summoner.getPuuid(), queueTypeForDbQuery, matchEndTime);

                if (recordBeforeOpt.isPresent() && recordAfterOpt.isPresent()) {
                    PlayerLpRecord recordBefore = recordBeforeOpt.get();
                    PlayerLpRecord recordAfter = recordAfterOpt.get();

                    if (recordAfter.getTimestamp().isBefore(matchEndTime)) {
                        logger.debug("LP record after match {} for PUUID {} (queue {}) occurs before match end time {}.",
                                match.getMetadata().getMatchId(), summoner.getPuuid(), queueTypeForDbQuery, matchEndTime);
                        continue;
                    }

                    int lpBefore = recordBefore.getLeaguePoints();
                    int lpAfter = recordAfter.getLeaguePoints();
                    int lpChange = lpAfter - lpBefore;

                    if (!recordBefore.getTier().equals(recordAfter.getTier()) || !recordBefore.getRank().equals(recordAfter.getRank())) {
                        logger.warn("Tier/Rank changed for match {}. PUUID: {}. Before: {} {} {} LP, After: {} {} {} LP. LP Change calculation might be inaccurate or represent promotion/demotion.",
                                match.getMetadata().getMatchId(), summoner.getPuuid(),
                                recordBefore.getTier(), recordBefore.getRank(), recordBefore.getLeaguePoints(),
                                recordAfter.getTier(), recordAfter.getRank(), recordAfter.getLeaguePoints());
                        match.getInfo().setLpChange(null);
                    } else {
                        match.getInfo().setLpChange(lpChange);
                    }
                } else {
                    logger.debug("LP records before or after match {} not found for PUUID {} and queue {}. Cannot calculate LP change.",
                            match.getMetadata().getMatchId(), summoner.getPuuid(), queueTypeForDbQuery);
                }
            } catch (Exception e) {
                logger.error("Error calculating LP change for match {} PUUID {}: {}", match.getMetadata().getMatchId(), summoner.getPuuid(), e.getMessage(), e);
            }
        }
    }
}
