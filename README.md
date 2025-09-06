# SummonerAPI — Your Personal League Stats Tracker

SummonerAPI is a Spring Boot web app that uses the Riot Games API to fetch and display League of Legends summoner profiles, rankings, and recent match histories — all in a clean and simple web interface.

> Disclaimer: This project is not affiliated with or endorsed by Riot Games. League of Legends and Riot Games are trademarks or registered trademarks of Riot Games, Inc. This tool uses the Riot Games API in accordance with their Developer Terms of Service.

---

## Features

- Summoner lookup by Riot ID (e.g., `YourName#EUW`)
- Profile overview (level, icon, PUUID)
- Ranked stats (tier, rank, wins/losses)
- Match history (recent games)
- LP history (optional via PostgreSQL)
- Lightweight UI with Thymeleaf

---

## Tech Stack

- Java 21
- Spring Boot 3.2.x
- Maven
- Thymeleaf
- Lombok
- Caffeine (cache)
- PostgreSQL + Spring Data JPA (LP history)

---

## Getting Started

### 1) Requirements

- JDK 21+
- Maven
- Riot API key
- PostgreSQL (optional, for LP history)

### 2) Configure

Option A — properties file:
- Copy `src/main/resources/application-example.properties` to `src/main/resources/application.properties`.
- Fill in your values (at minimum `riot.api.key`).

Option B — environment variables (recommended):
- `RIOT_API_KEY`, `RIOT_API_REGION`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`

### 3) Build and Run

```bash
mvn clean package
java -jar target/riot-api-spring-2.0.jar
```

Open http://localhost:8080

---

## Actuator

Actuator is enabled. Exposed endpoints:
- `/actuator/health`
- `/actuator/info`

---

## Docker (Optional)

Build and run with Docker Compose:

```bash
docker compose up --build
```

This starts PostgreSQL and the app. Override environment variables in `docker-compose.yml` as needed.

---

## Project Structure

- `controller/` — Web endpoints and views
- `service/` — Business logic
- `client/` — Riot API HTTP client
- `model/` — DTOs and entities
- `repository/` — JPA repositories
- `config/` — Cache and app configuration
- `templates/` — Thymeleaf pages

---

## Contributing

1. Fork the repo
2. Create a branch: `git checkout -b feature/NewFeature`
3. Commit: `git commit -m "Add NewFeature"`
4. Push: `git push origin feature/NewFeature`
5. Open a Pull Request

---

## License

GPL v3 — see `LICENSE`.

---

## Notes

- Secrets are never logged. Configure via env vars or `application.properties`.
- The Riot API has strict rate limits. The client includes basic retry/backoff for 429/5xx.
