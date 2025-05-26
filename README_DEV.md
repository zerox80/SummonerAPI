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
    The application expects a database named `docy1_db` (as configured in `application.properties`). Create it using:
    ```sql
    CREATE DATABASE docy1_db;
    ```

3.  **Create the Application User:**
    The application uses the username `docy787878` and password `jQWnqwmenq33weuk1i23ui1238Q!` (as configured in `application.properties`).
    **Important:** For a real development or production environment, choose a strong, unique password.
    Create the user:
    ```sql
    CREATE USER docy787878 WITH PASSWORD 'jQWnqwmenq33weuk1i23ui1238Q!';
    ```

4.  **Grant Privileges:**
    The application user needs privileges to connect to the database and create/manage tables within the `public` schema.

    *   **Connect to the newly created database:**
        ```sql
        \\c docy1_db
        ```
        (If you are still connected as the superuser in `psql`)

    *   **Grant connect privilege on the database:**
        ```sql
        GRANT CONNECT ON DATABASE docy1_db TO docy787878;
        ```

    *   **Grant usage on the public schema:**
        This allows the user to access objects within the schema.
        ```sql
        GRANT USAGE ON SCHEMA public TO docy787878;
        ```

    *   **Grant create privilege on the public schema:**
        This allows the user to create tables, indexes, etc., in the schema. Hibernate needs this for `ddl-auto`.
        ```sql
        GRANT CREATE ON SCHEMA public TO docy787878;
        ```

    *   **(Optional but Recommended) Grant all privileges on the database (for simplicity in dev):**
        For a local development setup, you might grant all privileges on the database to the user.
        **Caution:** Do not do this in production environments.
        ```sql
        GRANT ALL PRIVILEGES ON DATABASE docy1_db TO docy787878;
        ```
        If you grant all privileges on the database, you might also want to ensure the user can create tables within the `public` schema, which the `GRANT CREATE ON SCHEMA public` above already covers. For a more comprehensive grant that includes future tables in the schema (often useful for Hibernate):
        ```sql
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO docy787878;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO docy787878;
        ```

5.  **Verify Configuration:**
    Ensure your `src/main/resources/application.properties` file has the correct database URL, username, and password:
    ```properties
    spring.datasource.url=jdbc:postgresql://localhost:5432/docy1_db
    spring.datasource.username=docy787878
    spring.datasource.password=jQWnqwmenq33weuk1i23ui1238Q!
    spring.datasource.driver-class-name=org.postgresql.Driver

    spring.jpa.hibernate.ddl-auto=update
    # For initial setup, 'create' can be useful to ensure tables are made
    # spring.jpa.hibernate.ddl-auto=create 
    spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
    spring.jpa.show-sql=true
    ```

After completing these steps and ensuring your entity classes are correctly annotated (`@Entity`, `@Id`), your Spring Boot application should be able to connect to the PostgreSQL database and automatically create/update the schema when it starts. 