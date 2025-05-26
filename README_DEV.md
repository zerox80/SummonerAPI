### Content

# Developer Readme: Database Setup (PostgreSQL)

This document outlines the steps to set up the PostgreSQL database for local development of the Riot API Spring application.

## Prerequisites

*   PostgreSQL installed and running
*   A PostgreSQL superuser account (e.g., `postgres`) or a user with privileges to create databases and users

## Security Notice ⚠️

**Never commit database credentials to version control!** This README uses placeholder values that should be replaced with your actual configuration.

## Setup Steps

### 1. Environment Configuration

Create a `.env` file in your project root (add this to `.gitignore`):

```bash
# Database Configuration
DB_NAME=your_database_name
DB_USERNAME=your_db_user
DB_PASSWORD=your_secure_password
DB_HOST=localhost
DB_PORT=5432
```

### 2. Connect to PostgreSQL

Connect to your PostgreSQL instance using `psql`:

```bash
sudo -u postgres psql
```

Or with a dedicated user:

```bash
psql -U your_superuser -d postgres
```

### 3. Create the Database

Replace `${DB_NAME}` with your chosen database name:

```sql
CREATE DATABASE ${DB_NAME};
```

### 4. Create the Application User

Replace placeholders with your chosen credentials:

```sql
CREATE USER ${DB_USERNAME} WITH PASSWORD '${DB_PASSWORD}';
```

**Important:** Use a strong, unique password for any environment.

### 5. Grant Privileges

Connect to the newly created database and grant necessary privileges:

```sql
-- Connect to the database
\c ${DB_NAME}

-- Grant basic connection privileges
GRANT CONNECT ON DATABASE ${DB_NAME} TO ${DB_USERNAME};

-- Grant schema privileges
GRANT USAGE ON SCHEMA public TO ${DB_USERNAME};
GRANT CREATE ON SCHEMA public TO ${DB_USERNAME};

-- For development environment (more permissive)
GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${DB_USERNAME};

-- Grant privileges on future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO ${DB_USERNAME};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO ${DB_USERNAME};
```

### 6. Application Configuration

Update your `src/main/resources/application.properties`:

```properties
# Database Configuration (using environment variables)
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA/Hibernate Configuration
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.show-sql=true

# Optional: For initial setup, use 'create' to ensure tables are created
# spring.jpa.hibernate.ddl-auto=create
```

### 7. Alternative: Using application-dev.properties

Create `src/main/resources/application-dev.properties` for development-specific settings:

```properties
# Development Database Configuration
spring.datasource.url=jdbc:postgresql://localhost:5432/your_dev_database
spring.datasource.username=your_dev_user
spring.datasource.password=your_dev_password

# Development-specific settings
spring.jpa.show-sql=true
spring.jpa.hibernate.ddl-auto=create-drop
logging.level.org.hibernate.SQL=DEBUG
```

Run with development profile:
```bash
java -jar your-app.jar --spring.profiles.active=dev
```

## Environment Variables Setup

### Option 1: IDE Configuration
Set environment variables in your IDE's run configuration.

### Option 2: Command Line
```bash
export DB_NAME=your_database_name
export DB_USERNAME=your_db_user
export DB_PASSWORD=your_secure_password
java -jar your-application.jar
```

### Option 3: Using .env with Spring Boot
Add to your `build.gradle` or `pom.xml` a dependency for dotenv:

**Gradle:**
```gradle
implementation 'me.paulschwarz:spring-dotenv:2.5.4'
```

**Maven:**
```xml
<dependency>
    <groupId>me.paulschwarz</groupId>
    <artifactId>spring-dotenv</artifactId>
    <version>2.5.4</version>
</dependency>
```

## Security Best Practices

1. **Never commit credentials** to version control
2. **Add `.env` to `.gitignore`**
3. **Use different credentials** for each environment (dev, staging, prod)
4. **Use strong passwords** with a mix of characters
5. **Rotate passwords regularly** in production environments
6. **Use connection pooling** for production applications
7. **Consider using Spring Profiles** for different environments

## Example .gitignore Entry

```gitignore
# Environment variables
.env
*.env

# Application properties with sensitive data
application-*.properties
!application.properties
```

## Troubleshooting

- **Connection refused**: Check if PostgreSQL is running and the host/port are correct
- **Authentication failed**: Verify username and password
- **Database does not exist**: Ensure the database was created successfully
- **Permission denied**: Check that the user has the necessary privileges

After completing these steps, your Spring Boot application should be able to connect to the PostgreSQL database securely without exposing sensitive information in your codebase.

--

### Content

# Developer Readme: Database Setup (PostgreSQL)

This document outlines the steps to set up the PostgreSQL database for local development of the Riot API Spring application.

## Prerequisites

*   PostgreSQL installed and running.
*   A PostgreSQL superuser account (e.g., `postgres`) or a user with privileges to create databases and users.

## Setup Steps

1.  **Connect to PostgreSQL:**
    Connect to your PostgreSQL instance using `psql` or your preferred database management tool. For `psql`, you might use:
    ```bash
    sudo -u postgres psql
    ```
    Or if you have a dedicated user:
    ```bash
    psql -U your_superuser -d postgres
    ```

2.  **Create the Database:**
    The application expects a database named `db_name` (as configured in `application.properties`). Create it using:
    ```sql
    CREATE DATABASE db_name;
    ```

3.  **Create the Application User:**
    The application uses the username `docy787878` and password `PASSWORD!` (as configured in `application.properties`).
    **Important:** For a real development or production environment, choose a strong, unique password.
    Create the user:
    ```sql
    CREATE USER user_name WITH PASSWORD 'PASSWORD';
    ```

4.  **Grant Privileges:**
    The application user needs privileges to connect to the database and create/manage tables within the `public` schema.

    *   **Connect to the newly created database:**
        ```sql
        \\c db_name
        ```
        (If you are still connected as the superuser in `psql`)

    *   **Grant connect privilege on the database:**
        ```sql
        GRANT CONNECT ON DATABASE db_name TO user_name;
        ```

    *   **Grant usage on the public schema:**
        This allows the user to access objects within the schema.
        ```sql
        GRANT USAGE ON SCHEMA public TO user_name;
        ```

    *   **Grant create privilege on the public schema:**
        This allows the user to create tables, indexes, etc., in the schema. Hibernate needs this for `ddl-auto`.
        ```sql
        GRANT CREATE ON SCHEMA public TO user_name;
        ```

    *   **(Optional but Recommended) Grant all privileges on the database (for simplicity in dev):**
        For a local development setup, you might grant all privileges on the database to the user.
        **Caution:** Do not do this in production environments.
        ```sql
        GRANT ALL PRIVILEGES ON DATABASE db_name TO user_name;
        ```
        If you grant all privileges on the database, you might also want to ensure the user can create tables within the `public` schema, which the `GRANT CREATE ON SCHEMA public` above already covers. For a more comprehensive grant that includes future tables in the schema (often useful for Hibernate):
        ```sql
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO user_name;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO user_name;
        ```

5.  **Verify Configuration:**
    Ensure your `src/main/resources/application.properties` file has the correct database URL, username, and password:
    ```properties
    spring.datasource.url=jdbc:postgresql://localhost:5432/db_name
    spring.datasource.username=user_name
    spring.datasource.password=PASSWORD
    spring.datasource.driver-class-name=org.postgresql.Driver

    spring.jpa.hibernate.ddl-auto=update
    # For initial setup, 'create' can be useful to ensure tables are made
    # spring.jpa.hibernate.ddl-auto=create 
    spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
    spring.jpa.show-sql=true
    ```

After completing these steps and ensuring your entity classes are correctly annotated (`@Entity`, `@Id`), your Spring Boot application should be able to connect to the PostgreSQL database and automatically create/update the schema when it starts.

