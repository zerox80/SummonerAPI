package com.zerox80.riotapi.service;

import com.zerox80.riotapi.client.RiotApiClient;
import com.zerox80.riotapi.model.*;
import com.zerox80.riotapi.repository.PlayerLpRecordRepository;
import com.zerox80.riotapi.service.PlayerLpRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.Optional;
import com.google.common.collect.Lists;

@Service
public class RiotApiService {

    private static final Logger logger = LoggerFactory.getLogger(RiotApiService.class);
    private final RiotApiClient riotApiClient;
    private final PlayerLpRecordService playerLpRecordService;
    private final PlayerLpRecordRepository playerLpRecordRepository;

    @Autowired
    public RiotApiService(RiotApiClient riotApiClient,
                          PlayerLpRecordService playerLpRecordService,
                          PlayerLpRecordRepository playerLpRecordRepository) {
        this.riotApiClient = riotApiClient;
        this.playerLpRecordService = playerLpRecordService;
        this.playerLpRecordRepository = playerLpRecordRepository;
    }

    public CompletableFuture<Summoner> getSummonerByRiotId(String gameName, String tagLine) {
        if (!StringUtils.hasText(gameName) || !StringUtils.hasText(tagLine)) {
            logger.error("Fehler: Spielname und Tagline dürfen nicht leer sein.");
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Suche nach Account: {}#{}...", gameName, tagLine);
        return riotApiClient.getAccountByRiotId(gameName, tagLine)
                .thenCompose(account -> {
                    if (account != null && StringUtils.hasText(account.getPuuid())) {
                        logger.info("Account gefunden, PUUID: {}. Suche nach Beschwörerdaten...", account.getPuuid());
                        return riotApiClient.getSummonerByPuuid(account.getPuuid())
                                .thenApply(summoner -> {
                                    if (summoner != null) {
                                        if (StringUtils.hasText(account.getGameName())) {
                                            summoner.setName(account.getGameName());
                                        } else {
                                            summoner.setName(gameName);
                                            logger.warn("Warning: gameName is missing from AccountDto for PUUID: {}. Using provided gameName for Summoner object.", account.getPuuid());
                                        }
                                    }
                                    return summoner;
                                });
                    } else {
                        logger.info("Account für {}#{} nicht gefunden oder PUUID fehlt.", gameName, tagLine);
                        return CompletableFuture.completedFuture(null);
                    }
                }).exceptionally(ex -> {
                    logger.error("Error fetching summoner data for {}#{}: {}", gameName, tagLine, ex.getMessage(), ex);
                    return null;
                });
    }

    public CompletableFuture<List<LeagueEntryDTO>> getLeagueEntries(String puuid) {
        if (!StringUtils.hasText(puuid)) {
            logger.error("Fehler: PUUID darf nicht leer sein.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        logger.info("Suche nach League Entries für PUUID: {}...", puuid);
        CompletableFuture<List<LeagueEntryDTO>> leagueEntriesFuture = riotApiClient.getLeagueEntriesByPuuid(puuid);

        return leagueEntriesFuture.thenCompose(leagueEntries -> {
            if (leagueEntries != null && !leagueEntries.isEmpty()) {
                Instant now = Instant.now();
                return playerLpRecordService.savePlayerLpRecordsAsync(puuid, leagueEntries, now)
                         .thenApply(v -> leagueEntries);
            } else {
                return CompletableFuture.completedFuture(leagueEntries);
            }
        }).exceptionally(ex -> {
            logger.error("Error fetching or processing league entries for puuid {}: {}", puuid, ex.getMessage(), ex);
            return Collections.emptyList();
        });
    }

    @Cacheable(value = "matchHistory", key = "#puuid + '-' + #numberOfMatches")
    public CompletableFuture<List<MatchV5Dto>> getMatchHistory(String puuid, int numberOfMatches) {
        if (!StringUtils.hasText(puuid)) {
            logger.error("Error: PUUID cannot be empty when fetching match history.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        if (numberOfMatches <= 0) {
            logger.error("Error: Number of matches to fetch must be positive.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        logger.info("Fetching last {} match IDs for PUUID: {}...", numberOfMatches, puuid);
        return riotApiClient.getMatchIdsByPuuid(puuid, numberOfMatches)
                .thenCompose(matchIds -> {
                    if (matchIds.isEmpty()) {
                        logger.info("No match IDs found for PUUID: {}", puuid);
                        return CompletableFuture.completedFuture(Collections.<MatchV5Dto>emptyList());
                    }
                    logger.info("Fetching details for {} matches in batches...", matchIds.size());

                    List<List<String>> batches = Lists.partition(matchIds, 5);

                    return batches.stream()
                        .map(this::fetchMatchBatch)
                        .reduce(CompletableFuture.completedFuture(new ArrayList<MatchV5Dto>()),
                            (accFuture, batchFuture) -> accFuture.thenCompose(list ->
                                batchFuture.thenApply(batchResult -> {
                                    if (batchResult != null) {
                                        list.addAll(batchResult.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList()));
                                    }
                                    return list;
                                })
                            )
                        );
                }).exceptionally(ex -> {
                    logger.error("Error fetching match history for puuid {}: {}", puuid, ex.getMessage(), ex);
                    return Collections.emptyList();
                });
    }

    private CompletableFuture<List<MatchV5Dto>> fetchMatchBatch(List<String> matchIdBatch) {
        List<CompletableFuture<MatchV5Dto>> matchDetailFutures = matchIdBatch.stream()
            .map(matchId -> riotApiClient.getMatchDetails(matchId)
                .exceptionally(ex -> {
                    logger.error("Error fetching details for match ID {}: {}", matchId, ex.getMessage());
                    return null;
                }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(matchDetailFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> matchDetailFutures.stream()
                .map(CompletableFuture::join)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList()));
    }

    public Map<String, Long> getChampionPlayCounts(List<MatchV5Dto> matches, String searchedPuuid) {
        if (matches == null || matches.isEmpty() || !StringUtils.hasText(searchedPuuid)) {
            return Collections.emptyMap();
        }

        return matches.stream()
                .filter(match -> match != null && match.getInfo() != null && match.getInfo().getParticipants() != null)
                .flatMap(match -> match.getInfo().getParticipants().stream())
                .filter(participant -> participant != null && searchedPuuid.equals(participant.getPuuid()) && StringUtils.hasText(participant.getChampionName()))
                .collect(Collectors.groupingBy(ParticipantDto::getChampionName, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    public List<SummonerSuggestionDTO> getSummonerSuggestions(String partialName, Map<String, SummonerSuggestionDTO> userHistoryDtoMap) {
        Stream<Map.Entry<String, SummonerSuggestionDTO>> stream;
        Map<String, SummonerSuggestionDTO> historyToUse = (userHistoryDtoMap != null) ? userHistoryDtoMap : Collections.emptyMap();

        if (!StringUtils.hasText(partialName)) {
            stream = historyToUse.entrySet().stream();
        } else {
            String lowerPartialName = partialName.toLowerCase();
            stream = historyToUse.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(lowerPartialName));
        }

        return stream
                .limit(10)
                .map(Map.Entry::getValue)
                .distinct()
                .collect(Collectors.toList());
    }

    public CompletableFuture<SummonerProfileData> getSummonerProfileDataAsync(String gameName, String tagLine) {
        return getSummonerByRiotId(gameName, tagLine)
                .thenCompose(summoner -> {
                    if (summoner == null || !StringUtils.hasText(summoner.getPuuid())) {
                        logger.warn("Summoner not found or PUUID is missing for {}#{}", gameName, tagLine);
                        return CompletableFuture.completedFuture(new SummonerProfileData("Summoner not found or PUUID missing."));
                    }

                    String displayRiotId = summoner.getName() + "#" + tagLine;
                    String iconUrl = "https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/" + summoner.getProfileIconId() + ".jpg";
                    SummonerSuggestionDTO suggestionDTO = new SummonerSuggestionDTO(displayRiotId, summoner.getProfileIconId(), summoner.getSummonerLevel(), iconUrl);

                    // ===================================================================================
                    // KORREKTUR: Ruft jetzt die neue Methode mit PUUID auf
                    // ===================================================================================
                    CompletableFuture<List<LeagueEntryDTO>> leagueEntriesFuture = getLeagueEntries(summoner.getPuuid());
                    
                    CompletableFuture<List<MatchV5Dto>> matchHistoryFuture = getMatchHistory(summoner.getPuuid(), 5);

                    return CompletableFuture.allOf(leagueEntriesFuture, matchHistoryFuture)
                            .thenApply(v -> {
                                List<LeagueEntryDTO> leagueEntries = leagueEntriesFuture.join();
                                List<MatchV5Dto> matchHistory = matchHistoryFuture.join();

                                calculateAndSetLpChanges(summoner, matchHistory);

                                Map<String, Long> championPlayCounts = getChampionPlayCounts(matchHistory, summoner.getPuuid());

                                return new SummonerProfileData(summoner, leagueEntries, matchHistory, suggestionDTO, championPlayCounts);
                            });
                })
                .exceptionally(ex -> {
                    logger.error("Error building summoner profile data for {}#{}: {}", gameName, tagLine, ex.getMessage(), ex);
                    return new SummonerProfileData("An error occurred while fetching summoner profile data: " + ex.getMessage());
                });
    }

    private void calculateAndSetLpChanges(Summoner summoner, List<MatchV5Dto> matchHistory) {
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
