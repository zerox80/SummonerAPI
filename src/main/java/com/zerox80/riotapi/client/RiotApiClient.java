package com.zerox80.riotapi.client;

import com.zerox80.riotapi.model.AccountDto;
import com.zerox80.riotapi.model.Summoner;
import com.zerox80.riotapi.model.LeagueEntryDTO;
import com.zerox80.riotapi.model.MatchV5Dto;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RiotApiClient {

    private static final Logger logger = LoggerFactory.getLogger(RiotApiClient.class);
    private final String apiKey;
    private final String platformRegion;
    private final String regionalRoute;
    private final HttpClient httpClient;
    private final Gson gson;

    private static final Type LEAGUE_LIST_TYPE = new TypeToken<List<LeagueEntryDTO>>() {}.getType();
    private static final Type MATCH_ID_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    public RiotApiClient(@Value("${riot.api.key}") String apiKey, 
                         @Value("${riot.api.region}") String platformRegion) {
        this.apiKey = apiKey;
        this.platformRegion = platformRegion.toLowerCase();
        this.regionalRoute = determineRegionalRoute(this.platformRegion);
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(virtualThreadExecutor)
                .build();
        this.gson = new Gson();
    }

    private String determineRegionalRoute(String platform) {
        switch (platform) {
            case "euw1", "eun1", "tr1", "ru":
                return "europe";
            case "na1", "br1", "lan1", "las1", "oc1":
                return "americas";
            case "kr", "jp1":
                return "asia";
            case "vn2":
                return "sea";
            default:
                logger.warn("Warning: Unknown platform region '{}' for determining regional route. Defaulting to platform itself.", platform);
                return platform;
        }
    }

    private <T> CompletableFuture<T> sendApiRequestAsync(String url, Class<T> responseClass, String requestType) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Riot-Token", this.apiKey)
                .timeout(Duration.ofSeconds(15))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(), responseClass);
                    } else if (response.statusCode() == 404) {
                        logger.warn("API Request ({}) to URL '{}' returned 404 Not Found.", requestType, url);
                        return null;
                    } else {
                        logger.error("API Request Failed ({}): {} - {} for URL: {}", requestType, response.statusCode(), response.body(), url);
                        throw new RuntimeException("API request (" + requestType + ") failed with status code: " + response.statusCode() + "; Body: " + response.body());
                    }
                });
    }

    private <T> CompletableFuture<List<T>> sendApiRequestAsyncForList(String url, Type listType, String requestType) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Riot-Token", this.apiKey)
                .timeout(Duration.ofSeconds(15))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(), listType);
                    } else if (response.statusCode() == 404) {
                        logger.warn("API Request ({}) to URL '{}' returned 404 Not Found. Returning empty list.", requestType, url);
                        return java.util.Collections.emptyList();
                    } else {
                        logger.error("API Request Failed ({}): {} - {} for URL: {}", requestType, response.statusCode(), response.body(), url);
                        throw new RuntimeException("API request (" + requestType + ") failed with status code: " + response.statusCode() + "; Body: " + response.body());
                    }
                });
    }

    @Cacheable(value = "accounts", key = "#gameName.toLowerCase() + '#' + #tagLine.toLowerCase()")
    public CompletableFuture<AccountDto> getAccountByRiotId(String gameName, String tagLine) {
        String encodedGameName = URLEncoder.encode(gameName, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedTagLine = URLEncoder.encode(tagLine, StandardCharsets.UTF_8);

        String host = this.regionalRoute + ".api.riotgames.com";
        String path = "/riot/account/v1/accounts/by-riot-id/" + encodedGameName + "/" + encodedTagLine;
        String url = "https://" + host + path;

        logger.debug(">>> RiotApiClient (Account): Requesting RAW Riot ID: [{}#{}]", gameName, tagLine);
        logger.debug(">>> RiotApiClient (Account): Requesting ENCODED URL: [{}]", url);

        return sendApiRequestAsync(url, AccountDto.class, "Account");
    }

    @Cacheable(value = "summoners", key = "#puuid")
    public CompletableFuture<Summoner> getSummonerByPuuid(String puuid) {
        String host = this.platformRegion + ".api.riotgames.com";
        String path = "/lol/summoner/v4/summoners/by-puuid/" + puuid;
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (Summoner): Requesting URL: [{}]", url);
        return sendApiRequestAsync(url, Summoner.class, "Summoner");
    }

    @Cacheable(value = "leagueEntries", key = "#encryptedSummonerId")
    public CompletableFuture<List<LeagueEntryDTO>> getLeagueEntriesBySummonerId(String encryptedSummonerId) {
        String host = this.platformRegion + ".api.riotgames.com";
        String path = "/lol/league/v4/entries/by-summoner/" + encryptedSummonerId;
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (LeagueEntries): Requesting URL: [{}]", url);
        return sendApiRequestAsyncForList(url, LEAGUE_LIST_TYPE, "LeagueEntries");
    }

    @Cacheable(value = "matchIds", key = "#puuid + '-' + #count")
    public CompletableFuture<List<String>> getMatchIdsByPuuid(String puuid, int count) {
        String host = this.regionalRoute + ".api.riotgames.com";
        String path = "/lol/match/v5/matches/by-puuid/" + puuid + "/ids?count=" + count;
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (MatchIds): Requesting URL: [{}]", url);
        return sendApiRequestAsyncForList(url, MATCH_ID_LIST_TYPE, "MatchIds");
    }

    @Cacheable(value = "matchDetails", key = "#matchId")
    public CompletableFuture<MatchV5Dto> getMatchDetails(String matchId) {
        String host = this.regionalRoute + ".api.riotgames.com";
        String path = "/lol/match/v5/matches/" + matchId;
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (MatchDetails): Requesting URL: [{}]", url);
        return sendApiRequestAsync(url, MatchV5Dto.class, "MatchDetails");
    }

    public String getPlatformRegion() {
        return platformRegion;
    }
}