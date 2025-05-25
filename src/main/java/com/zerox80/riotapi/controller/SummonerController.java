package com.zerox80.riotapi.controller;

import com.zerox80.riotapi.model.Summoner;
import com.zerox80.riotapi.model.LeagueEntryDTO;
import com.zerox80.riotapi.model.MatchV5Dto;
import com.zerox80.riotapi.model.SummonerSuggestionDTO;
import com.zerox80.riotapi.model.PlayerLpRecord;
import com.zerox80.riotapi.repository.PlayerLpRecordRepository;
import com.zerox80.riotapi.service.RiotApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.util.Optional;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;

@Controller
public class SummonerController {

    private static final Logger logger = LoggerFactory.getLogger(SummonerController.class);
    private final RiotApiService riotApiService;
    private final PlayerLpRecordRepository playerLpRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SEARCH_HISTORY_COOKIE = "searchHistory";
    private static final int MAX_HISTORY_SIZE = 10;

    @Autowired
    public SummonerController(RiotApiService riotApiService, PlayerLpRecordRepository playerLpRecordRepository) {
        this.riotApiService = riotApiService;
        this.playerLpRecordRepository = playerLpRecordRepository;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/api/summoner-suggestions")
    @ResponseBody
    public List<SummonerSuggestionDTO> summonerSuggestions(@RequestParam("query") String query, HttpServletRequest request) {
        Map<String, SummonerSuggestionDTO> userHistory = getSearchHistoryFromCookie(request);
        return riotApiService.getSummonerSuggestions(query, userHistory);
    }

    @RequestMapping(value = "/search", method = {RequestMethod.GET, RequestMethod.POST})
    public Callable<String> searchSummoner(@RequestParam("riotId") String riotId, Model model, HttpServletRequest request, HttpServletResponse response) {
        if (!StringUtils.hasText(riotId) || !riotId.contains("#")) {
            model.addAttribute("error", "Invalid Riot ID. Please use the format Name#TAG.");
            return () -> "index";
        }

        String[] parts = riotId.split("#", 2);
        String gameName = parts[0];
        String tagLine = parts[1];

        if (!StringUtils.hasText(gameName) || !StringUtils.hasText(tagLine)) {
            model.addAttribute("error", "Invalid Riot ID. Name and Tagline cannot be empty.");
            return () -> "index";
        }

        return () -> {
            try {
                CompletableFuture<Summoner> summonerFuture = riotApiService.getSummonerByRiotId(gameName, tagLine);
                Summoner summoner = summonerFuture.join();

                if (summoner != null) {
                    model.addAttribute("summoner", summoner);

                    String displayRiotId = summoner.getName() + "#" + tagLine;
                    String iconUrl = "https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/" + summoner.getProfileIconId() + ".jpg";
                    SummonerSuggestionDTO suggestionDTO = new SummonerSuggestionDTO(displayRiotId, summoner.getProfileIconId(), summoner.getSummonerLevel(), iconUrl);
                    updateSearchHistoryCookie(request, response, riotId, suggestionDTO);

                    CompletableFuture<List<LeagueEntryDTO>> leagueEntriesFuture = riotApiService.getLeagueEntriesBySummonerId(summoner.getId(), summoner.getPuuid());
                    CompletableFuture<List<MatchV5Dto>> matchHistoryFuture = StringUtils.hasText(summoner.getPuuid()) ?
                            riotApiService.getMatchHistory(summoner.getPuuid(), 20) :
                            CompletableFuture.completedFuture(Collections.emptyList());

                    CompletableFuture.allOf(leagueEntriesFuture, matchHistoryFuture).join();

                    List<LeagueEntryDTO> leagueEntries = Collections.emptyList();
                    List<MatchV5Dto> matchHistory = Collections.emptyList();

                    try {
                        leagueEntries = leagueEntriesFuture.get();
                        model.addAttribute("leagueEntries", leagueEntries);
                    } catch (Exception e_league) {
                        logger.error("Error fetching league entries for summoner {}: {}", summoner.getId(), e_league.getMessage(), e_league);
                        model.addAttribute("leagueError", "Error fetching league data: " + e_league.getMessage());
                        model.addAttribute("leagueEntries", Collections.emptyList());
                    }

                    if (StringUtils.hasText(summoner.getPuuid())) {
                        try {
                            matchHistory = matchHistoryFuture.get();

                            if (matchHistory != null && !matchHistory.isEmpty()) {
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

                                    Optional<PlayerLpRecord> recordBeforeOpt = playerLpRecordRepository
                                            .findFirstByPuuidAndQueueTypeAndTimestampBeforeOrderByTimestampDesc(
                                                    summoner.getPuuid(), queueTypeForDbQuery, matchEndTime);

                                    Optional<PlayerLpRecord> recordAfterOpt = playerLpRecordRepository
                                            .findFirstByPuuidAndQueueTypeAndTimestampGreaterThanEqualOrderByTimestampAsc(
                                                    summoner.getPuuid(), queueTypeForDbQuery, matchEndTime);

                                    if (recordBeforeOpt.isPresent() && recordAfterOpt.isPresent()) {
                                        PlayerLpRecord recordBefore = recordBeforeOpt.get();
                                        PlayerLpRecord recordAfter = recordAfterOpt.get();

                                        if (recordAfter.getTimestamp().isAfter(recordBefore.getTimestamp()) || recordAfter.getTimestamp().equals(recordBefore.getTimestamp())) {
                                            int lpBefore = recordBefore.getLeaguePoints();
                                            int lpAfter = recordAfter.getLeaguePoints();

                                            int lpChange = lpAfter - lpBefore;

                                            if (!recordBefore.getTier().equals(recordAfter.getTier()) || !recordBefore.getRank().equals(recordAfter.getRank())) {
                                                logger.warn("Tier/Rank changed for match {}. PUUID: {}. Before: {} {} {} LP, After: {} {} {} LP. LP Change calculation might be inaccurate.",
                                                    match.getMetadata().getMatchId(), summoner.getPuuid(),
                                                    recordBefore.getTier(), recordBefore.getRank(), recordBefore.getLeaguePoints(),
                                                    recordAfter.getTier(), recordAfter.getRank(), recordAfter.getLeaguePoints());
                                                match.getInfo().setLpChange(null);
                                            } else {
                                                 match.getInfo().setLpChange(lpChange);
                                            }
                                        }
                                    } else {
                                        logger.debug("LP records before or after match {} not found for PUUID {} and queue {}. Cannot calculate LP change.",
                                                    match.getMetadata().getMatchId(), summoner.getPuuid(), queueTypeForDbQuery);
                                    }
                                }
                            }
                            model.addAttribute("matchHistory", matchHistory);

                            if (matchHistory != null && !matchHistory.isEmpty()) {
                                Map<String, Long> championPlayCounts = riotApiService.getChampionPlayCounts(matchHistory, summoner.getPuuid());
                                model.addAttribute("championPlayCounts", championPlayCounts);
                            }

                            if (matchHistory.isEmpty() && !model.containsAttribute("matchHistoryError")) {
                                model.addAttribute("matchHistoryInfo", "No recent matches found.");
                            }
                        } catch (Exception e_match) {
                            logger.error("Error fetching match history for PUUID {}: {}", summoner.getPuuid(), e_match.getMessage(), e_match);
                            model.addAttribute("matchHistoryError", "Error fetching match history: " + e_match.getMessage());
                            model.addAttribute("matchHistory", Collections.emptyList());
                        }
                    } else {
                        model.addAttribute("matchHistoryInfo", "PUUID not available, cannot fetch match history.");
                        model.addAttribute("matchHistory", Collections.emptyList());
                    }
                } else {
                    model.addAttribute("error", "Summoner '" + riotId + "' not found.");
                    model.addAttribute("matchHistory", Collections.emptyList());
                    model.addAttribute("matchHistoryInfo", "Summoner not found, cannot display match history.");
                }

                if (!model.containsAttribute("championPlayCounts")) {
                    model.addAttribute("championPlayCounts", Collections.emptyMap());
                }

            } catch (Exception e) {
                model.addAttribute("error", "An error occurred while fetching data: " + e.getMessage());
                logger.error("Error fetching summoner data for {}: {}", riotId, e.getMessage(), e);
                model.addAttribute("matchHistory", Collections.emptyList());
                model.addAttribute("matchHistoryInfo", "An error occurred, cannot display match history.");
                model.addAttribute("championPlayCounts", Collections.emptyMap());
            }
            return "index";
        };
    }

    private Map<String, SummonerSuggestionDTO> getSearchHistoryFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SEARCH_HISTORY_COOKIE.equals(cookie.getName())) {
                    try {
                        String decodedValue = URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8.name());
                        return objectMapper.readValue(decodedValue, new TypeReference<LinkedHashMap<String, SummonerSuggestionDTO>>() {});
                    } catch (IOException | IllegalArgumentException e) {
                        logger.error("Error reading search history cookie: " + e.getMessage(), e);
                        return new LinkedHashMap<>();
                    }
                }
            }
        }
        return new LinkedHashMap<>();
    }

    private void updateSearchHistoryCookie(HttpServletRequest request, HttpServletResponse response, String riotId, SummonerSuggestionDTO suggestionDTO) {
        Map<String, SummonerSuggestionDTO> history = getSearchHistoryFromCookie(request);

        history.remove(riotId.toLowerCase());
        history.put(riotId.toLowerCase(), suggestionDTO);

        while (history.size() > MAX_HISTORY_SIZE) {
            String oldestKey = history.keySet().iterator().next();
            history.remove(oldestKey);
        }

        try {
            String jsonHistory = objectMapper.writeValueAsString(history);
            String encodedValue = URLEncoder.encode(jsonHistory, StandardCharsets.UTF_8.name());
            Cookie cookie = new Cookie(SEARCH_HISTORY_COOKIE, encodedValue);
            cookie.setPath("/");
            cookie.setMaxAge(30 * 24 * 60 * 60);
            response.addCookie(cookie);
        } catch (IOException e) {
            logger.error("Error writing search history cookie: " + e.getMessage(), e);
        }
    }
} 