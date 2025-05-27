package com.zerox80.riotapi.model;

import java.util.List;
import java.util.Map;

public record SummonerProfileData(
    Summoner summoner,
    List<LeagueEntryDTO> leagueEntries,
    List<MatchV5Dto> matchHistory,
    SummonerSuggestionDTO suggestion,
    Map<String, Long> championPlayCounts,
    String errorMessage // Optional: um Fehlerdetails vom Service zum Controller zu transportieren
) {
    // Konstruktor für den Erfolgsfall
    public SummonerProfileData(Summoner summoner, List<LeagueEntryDTO> leagueEntries, List<MatchV5Dto> matchHistory, SummonerSuggestionDTO suggestion, Map<String, Long> championPlayCounts) {
        this(summoner, leagueEntries, matchHistory, suggestion, championPlayCounts, null);
    }

    // Konstruktor für den Fehlerfall
    public SummonerProfileData(String errorMessage) {
        this(null, List.of(), List.of(), null, Map.of(), errorMessage);
    }

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }
} 
