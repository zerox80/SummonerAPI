package com.zerox80.riotapi.controller;

import com.zerox80.riotapi.model.Summoner;
import com.zerox80.riotapi.model.LeagueEntryDTO;
import com.zerox80.riotapi.model.MatchV5Dto;
import com.zerox80.riotapi.model.SummonerSuggestionDTO;
import com.zerox80.riotapi.service.RiotApiService;
import com.zerox80.riotapi.model.SummonerProfileData;
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
import java.util.concurrent.CompletionException;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SEARCH_HISTORY_COOKIE = "searchHistory";
    private static final int MAX_HISTORY_SIZE = 10;

    @Autowired
    public SummonerController(RiotApiService riotApiService) {
        this.riotApiService = riotApiService;
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
                CompletableFuture<SummonerProfileData> profileDataFuture = riotApiService.getSummonerProfileDataAsync(gameName, tagLine);
                SummonerProfileData profileData = profileDataFuture.join();

                if (profileData.hasError()) {
                    model.addAttribute("error", profileData.errorMessage());
                    model.addAttribute("summoner", null);
                    model.addAttribute("leagueEntries", Collections.emptyList());
                    model.addAttribute("matchHistory", Collections.emptyList());
                    model.addAttribute("championPlayCounts", Collections.emptyMap());
                    model.addAttribute("matchHistoryInfo", profileData.errorMessage());
                    return "index";
                }

                model.addAttribute("summoner", profileData.summoner());
                model.addAttribute("leagueEntries", profileData.leagueEntries());
                model.addAttribute("matchHistory", profileData.matchHistory());
                model.addAttribute("championPlayCounts", profileData.championPlayCounts());

                if (profileData.summoner() != null && profileData.suggestion() != null) {
                    updateSearchHistoryCookie(request, response, riotId, profileData.suggestion());
                }
                
                if (profileData.matchHistory() == null || profileData.matchHistory().isEmpty()) {
                    if (!model.containsAttribute("error")) {
                        model.addAttribute("matchHistoryInfo", "No recent matches found or PUUID not available.");
                    }
                }

            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                logger.error("Error processing summoner search for Riot ID '{}': {}", riotId, cause.getMessage(), cause);
                model.addAttribute("error", "An error occurred: " + cause.getMessage());
                model.addAttribute("leagueEntries", Collections.emptyList());
                model.addAttribute("matchHistory", Collections.emptyList());
                model.addAttribute("championPlayCounts", Collections.emptyMap());
                model.addAttribute("matchHistoryInfo", "An error occurred while fetching data.");
            } catch (Exception e) {
                logger.error("Unexpected error during summoner search for Riot ID '{}': {}", riotId, e.getMessage(), e);
                model.addAttribute("error", "An unexpected error occurred: " + e.getMessage());
                model.addAttribute("leagueEntries", Collections.emptyList());
                model.addAttribute("matchHistory", Collections.emptyList());
                model.addAttribute("championPlayCounts", Collections.emptyMap());
                model.addAttribute("matchHistoryInfo", "An unexpected error occurred.");
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