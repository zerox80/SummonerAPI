# SummonerAPI – Your Personal League Stats Tracker

**SummonerAPI** is a Spring Boot web app that leverages the Riot Games API to fetch and display League of Legends summoner profiles, rankings, and recent match histories — all in a clean and simple web interface.

> ⚠️ **Disclaimer:** This project is not affiliated with or endorsed by Riot Games. League of Legends and Riot Games are trademarks or registered trademarks of Riot Games, Inc.  
> This tool uses the [Riot Games API](https://developer.riotgames.com/) in accordance with their [Developer Terms of Service](https://developer.riotgames.com/docs/portal#_legal).

---

## 🚀 Features

- 🔍 **Summoner Lookup:** Enter a Riot ID (e.g., `YourName#EUW`) and retrieve their profile.
- 📊 **Profile Overview:** Summoner level, icon, and PUUID display.
- 🏆 **Ranked Stats:** View current rank, tier, and win/loss records for all queues.
- 📜 **Match History:** See a list of recent matches.
- 📈 **LP History (Experimental):** Saves ranked data points in a PostgreSQL database to calculate LP changes per game.
- 💻 **Web Interface:** Built with Thymeleaf for a smooth and lightweight user experience.

---

## 🧰 Tech Stack

- Java 21
- Spring Boot 3.2.5
- Maven (build & dependencies)
- Thymeleaf (HTML templates)
- Lombok (to reduce boilerplate)
- Caffeine (API response caching)
- **PostgreSQL** (for LP history)
- **Spring Data JPA** (for database access)
- Riot Games API

---

## ⚙️ Getting Started

### 1. Requirements

- **JDK 21 or newer:** [Download here](https://www.oracle.com/java/technologies/downloads/)
- **Maven:** [Install here](https://maven.apache.org/download.cgi)
- **Riot API Key:** [Apply for one](https://developer.riotgames.com/)
- **PostgreSQL:** A running PostgreSQL instance.

### 2. Setup

```bash
git clone https://github.com/zerox80/SummonerAPI.git
cd SummonerAPI
```

#### Configuration

Open `src/main/resources/application.properties` and update the following:

```properties
# Riot API
riot.api.key=YOUR_API_KEY_HERE
riot.api.region=euw1 # e.g., na1, kr, eun1

# Server Port
server.port=8080      # Change if needed
```

#### Database Configuration

This project uses PostgreSQL to store LP history. Update these properties in `application.properties` with your database credentials:

```properties
# PostgreSQL Datasource
spring.datasource.url=jdbc:postgresql://localhost:5432/YOUR_DATABASE
spring.datasource.username=YOUR_USERNAME
spring.datasource.password=YOUR_PASSWORD
```

### 3. Build and Run

```bash
mvn clean install
java -jar target/summoner-api-0.0.1-SNAPSHOT.jar
```

Then open `http://localhost:8080` in your browser.

---

## 🧠 Project Structure

- `SummonerController` – Handles web requests, invokes services, sends data to views.
- `RiotApiService` – Business logic and orchestration of API calls.
- `RiotApiClient` – Low-level HTTP calls to the Riot API.
- `model/` – DTOs mirroring Riot API responses.
- `config/` – Includes `CacheConfig` (Caffeine) and other Spring beans.
- `templates/` – Thymeleaf HTML pages.

---

## 🌱 Roadmap Ideas

- Match details with advanced stats
- Champion mastery data
- Live match tracking
- Enhanced UI (possibly Bootstrap or TailwindCSS)
- Improved error feedback

---

## 🤝 Contributing

Contributions are welcome!

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/NewFeature`
3. Commit: `git commit -m "Add NewFeature"`
4. Push: `git push origin feature/NewFeature`
5. Open a Pull Request

---

## 📄 License

This project is licensed under the **GNU GPL v3**. See the [`LICENSE`](./LICENSE) file for full details.

---

## 📬 Feedback & Contact

Found a bug or want to suggest a feature? Open an issue or a pull request — or just drop a star if you find the project useful! 🌟
