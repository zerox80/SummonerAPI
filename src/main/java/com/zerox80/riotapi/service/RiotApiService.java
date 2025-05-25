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

@Service
public class RiotApiService {

    private static final Logger logger = LoggerFactory.getLogger(RiotApiService.class);
    private final RiotApiClient riotApiClient;
    private final PlayerLpRecordService playerLpRecordService;

    @Autowired
    public RiotApiService(RiotApiClient riotApiClient,
                          PlayerLpRecordService playerLpRecordService) {
        this.riotApiClient = riotApiClient;
        this.playerLpRecordService = playerLpRecordService;
    }

    @Cacheable(value = "summoners", key = "#gameName.toLowerCase() + '#' + #tagLine.toLowerCase()")
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

    @Cacheable(value = "leagueEntries", key = "#encryptedSummonerId")
    public CompletableFuture<List<LeagueEntryDTO>> getLeagueEntriesBySummonerId(String encryptedSummonerId, String puuidForRecord) {
        if (!StringUtils.hasText(encryptedSummonerId)) {
            logger.error("Fehler: encryptedSummonerId darf nicht leer sein.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        logger.info("Suche nach League Entries für Summoner ID: {}...", encryptedSummonerId);
        CompletableFuture<List<LeagueEntryDTO>> leagueEntriesFuture = riotApiClient.getLeagueEntriesBySummonerId(encryptedSummonerId);

        return leagueEntriesFuture.thenCompose(leagueEntries -> {
            if (leagueEntries != null && !leagueEntries.isEmpty() && StringUtils.hasText(puuidForRecord)) {
                return playerLpRecordService.savePlayerLpRecordsAsync(puuidForRecord, leagueEntries)
                         .thenApply(v -> leagueEntries);
            } else {
                return CompletableFuture.completedFuture(leagueEntries);
            }
        }).exceptionally(ex -> {
            logger.error("Error fetching or processing league entries for summonerId {}: {}", encryptedSummonerId, ex.getMessage(), ex);
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
                    logger.info("Fetching details for {} matches...", matchIds.size());

                    List<CompletableFuture<MatchV5Dto>> matchDetailFutures = new ArrayList<>(matchIds.size());
                    for (String matchId : matchIds) {
                        matchDetailFutures.add(
                            riotApiClient.getMatchDetails(matchId)
                                .exceptionally(ex -> {
                                    logger.error("Error fetching details for match ID {}: {}", matchId, ex.getMessage());
                                    return null;
                                })
                        );
                    }

                    return CompletableFuture.allOf(matchDetailFutures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> matchDetailFutures.stream()
                                    .map(CompletableFuture::join)
                                    .filter(java.util.Objects::nonNull)
                                    .collect(Collectors.toList()));
                }).exceptionally(ex -> {
                    logger.error("Error fetching match history for puuid {}: {}", puuid, ex.getMessage(), ex);
                    return Collections.emptyList();
                });
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

}