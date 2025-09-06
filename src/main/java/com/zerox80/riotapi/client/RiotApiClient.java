package com.zerox80.riotapi.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zerox80.riotapi.model.AccountDto;
import com.zerox80.riotapi.model.LeagueEntryDTO;
import com.zerox80.riotapi.model.MatchV5Dto;
import com.zerox80.riotapi.model.Summoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
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
    private final ObjectMapper objectMapper;
    private final String communityDragonUrl;

    private static final TypeReference<List<LeagueEntryDTO>> LEAGUE_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> MATCH_ID_LIST_TYPE = new TypeReference<>() {};

    @Autowired
    public RiotApiClient(@Value("${riot.api.key}") String apiKey,
                         @Value("${riot.api.region}") String platformRegion,
                         @Value("${riot.api.community-dragon.url}") String communityDragonUrl,
                         ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.platformRegion = platformRegion.toLowerCase();
        this.regionalRoute = determineRegionalRoute(this.platformRegion);
        this.communityDragonUrl = communityDragonUrl;
        // Copy and harden the mapper: Riot APIs use lowerCamelCase; ignore unknown fields
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(virtualThreadExecutor)
                .build();

        if (this.apiKey == null || this.apiKey.isBlank() || "YOUR_API_KEY".equalsIgnoreCase(this.apiKey)) {
            logger.warn("Riot API key is missing or placeholder. Set property 'riot.api.key' or env 'RIOT_API_KEY'.");
        }
    }

    public String getProfileIconUrl(int iconId) {
        return communityDragonUrl + "/" + iconId + ".jpg";
    }

    private String determineRegionalRoute(String platform) {
        switch (platform) {
            case "euw1", "eun1", "tr1", "ru", "me1":
                return "europe";
            case "na1", "br1", "la1", "la2", "oc1":
                return "americas";
            case "kr", "jp1":
                return "asia";
            case "vn2", "ph2", "sg2", "th2", "tw2", "id1":
                return "sea";
            default:
                logger.warn("Warning: Unknown platform region '{}' for determining regional route. Defaulting to platform itself.", platform);
                return platform;
        }
    }

    private <T> CompletableFuture<T> sendApiRequestAsync(String url, Class<T> responseClass, String requestType) {
        return sendRequest(url, requestType)
                .thenApply(response -> parseResponse(response, responseClass, requestType, url));
    }

    private <T> CompletableFuture<T> sendApiRequestAsync(String url, TypeReference<T> typeReference, String requestType) {
        return sendRequest(url, requestType)
                .thenApply(response -> parseResponse(response, typeReference, requestType, url));
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(2);

    private CompletableFuture<HttpResponse<String>> sendRequest(String url, String requestType) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Riot-Token", this.apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "SummonerAPI/2.0 (github.com/zerox80/SummonerAPI)")
                .timeout(Duration.ofSeconds(15))
                .build();
        return sendWithRetry(request, requestType, url, 1);
    }

    private CompletableFuture<HttpResponse<String>> sendRequestWithBearer(String url, String requestType, String bearerToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .header("User-Agent", "SummonerAPI/2.0 (github.com/zerox80/SummonerAPI)")
                .timeout(Duration.ofSeconds(15))
                .build();
        return sendWithRetry(request, requestType, url, 1);
    }

    private CompletableFuture<HttpResponse<String>> sendWithRetry(HttpRequest request, String requestType, String url, int attempt) {
        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, throwable) -> new Object[]{response, throwable})
                .thenCompose(pair -> {
                    @SuppressWarnings("unchecked")
                    HttpResponse<String> response = (HttpResponse<String>) pair[0];
                    Throwable throwable = (Throwable) pair[1];

                    if (throwable != null) {
                        if (attempt < MAX_ATTEMPTS) {
                            Duration delay = computeBackoffDelay(attempt, Optional.empty());
                            logger.warn("Request {} to {} failed (attempt {}/{}). Retrying in {} ms. Cause: {}",
                                    requestType, url, attempt, MAX_ATTEMPTS, delay.toMillis(), throwable.toString());
                            return delayed(delay).thenCompose(v -> sendWithRetry(request, requestType, url, attempt + 1));
                        }
                        CompletableFuture<HttpResponse<String>> failed = new CompletableFuture<>();
                        failed.completeExceptionally(throwable);
                        return failed;
                    }

                    int status = response.statusCode();
                    if ((status == 429 || (status >= 500 && status < 600)) && attempt < MAX_ATTEMPTS) {
                        Optional<Long> retryAfterSeconds = parseRetryAfterSeconds(response);
                        Duration delay = computeBackoffDelay(attempt, retryAfterSeconds);
                        logger.warn("Request {} to {} returned {}. Retrying in {} ms (attempt {}/{}).",
                                requestType, url, status, delay.toMillis(), attempt, MAX_ATTEMPTS);
                        return delayed(delay).thenCompose(v -> sendWithRetry(request, requestType, url, attempt + 1));
                    }

                    return CompletableFuture.completedFuture(response);
                });
    }

    private Optional<Long> parseRetryAfterSeconds(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                return Optional.of(Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private Duration computeBackoffDelay(int attempt, Optional<Long> retryAfterSeconds) {
        if (retryAfterSeconds.isPresent()) {
            return Duration.ofSeconds(Math.max(1, retryAfterSeconds.get()));
        }
        long jitterMs = ThreadLocalRandom.current().nextLong(100, 400);
        long backoffMs = BASE_BACKOFF.toMillis() * (long) Math.pow(2, attempt - 1);
        return Duration.ofMillis(backoffMs + jitterMs);
    }

    private CompletableFuture<Void> delayed(Duration delay) {
        return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)).thenAccept(v -> {});
    }

    private <T> T parseResponse(HttpResponse<String> response, Class<T> responseClass, String requestType, String url) {
        if (response.statusCode() == 200) {
            try {
                return objectMapper.readValue(response.body(), responseClass);
            } catch (JsonProcessingException e) {
                throw new RiotApiRequestException("Failed to parse API response for " + requestType, e);
            }
        } else if (response.statusCode() == 404) {
            logger.warn("API Request ({}) to URL '{}' returned 404 Not Found.", requestType, url);
            return null;
        } else {
            logger.error("API Request Failed ({}): {} - {} for URL: {}", requestType, response.statusCode(), response.body(), url);
            throw new RiotApiRequestException("API request (" + requestType + ") failed with status code: " + response.statusCode() + "; Body: " + response.body());
        }
    }

    private <T> T parseResponse(HttpResponse<String> response, TypeReference<T> typeReference, String requestType, String url) {
        if (response.statusCode() == 200) {
            try {
                return objectMapper.readValue(response.body(), typeReference);
            } catch (JsonProcessingException e) {
                throw new RiotApiRequestException("Failed to parse API response for " + requestType, e);
            }
        } else if (response.statusCode() == 404) {
            logger.warn("API Request ({}) to URL '{}' returned 404 Not Found.", requestType, url);
            return null;
        } else {
            logger.error("API Request Failed ({}): {} - {} for URL: {}", requestType, response.statusCode(), response.body(), url);
            throw new RiotApiRequestException("API request (" + requestType + ") failed with status code: " + response.statusCode() + "; Body: " + response.body());
        }
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

    /**
     * Fetches league entries (rank, tier, etc.) by the encrypted summoner ID.
     * Official endpoint: /lol/league/v4/entries/by-summoner/{encryptedSummonerId}
     */
    @Cacheable(value = "leagueEntries", key = "#summonerId")
    public CompletableFuture<List<LeagueEntryDTO>> getLeagueEntriesBySummonerId(String summonerId) {
        String host = this.platformRegion + ".api.riotgames.com";
        String path = "/lol/league/v4/entries/by-summoner/" + summonerId;
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (LeagueEntries): Requesting URL: [{}]", url);
        return sendApiRequestAsync(url, LEAGUE_LIST_TYPE, "LeagueEntries");
    }

    /**
     * Fetches league entries using PUUID to prepare for removal of SummonerIDs from payloads.
     * Official endpoint: /lol/league/v4/entries/by-puuid/{puuid}
     */
    @Cacheable(value = "leagueEntries", key = "#puuid")
    public CompletableFuture<List<LeagueEntryDTO>> getLeagueEntriesByPuuid(String puuid) {
        String host = this.platformRegion + ".api.riotgames.com";
        String path = "/lol/league/v4/entries/by-puuid/" + puuid;
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (LeagueEntries PUUID): Requesting URL: [{}]", url);
        return sendApiRequestAsync(url, LEAGUE_LIST_TYPE, "LeagueEntriesByPuuid");
    }

    /**
     * RSO-based endpoint to fetch the summoner of the authenticated user.
     * Requires an OAuth 2.0 Bearer token (RSO), not the X-Riot-Token.
     */
    public CompletableFuture<Summoner> getSummonerMeWithBearer(String bearerToken) {
        String host = this.platformRegion + ".api.riotgames.com";
        String path = "/lol/summoner/v4/summoners/me";
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (SummonerMe RSO): Requesting URL: [{}]", url);
        return sendRequestWithBearer(url, "SummonerMeRSO", bearerToken)
                .thenApply(response -> parseResponse(response, Summoner.class, "SummonerMeRSO", url));
    }

    @Cacheable(value = "matchIds", key = "#puuid + '-' + #count")
    public CompletableFuture<List<String>> getMatchIdsByPuuid(String puuid, int count) {
        String host = this.regionalRoute + ".api.riotgames.com";
        String path = "/lol/match/v5/matches/by-puuid/" + puuid + "/ids?count=" + count;
        String url = "https://" + host + path;
        logger.debug(">>> RiotApiClient (MatchIds): Requesting URL: [{}]", url);
        return sendApiRequestAsync(url, MATCH_ID_LIST_TYPE, "MatchIds");
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
