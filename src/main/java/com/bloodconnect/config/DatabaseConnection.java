package com.bloodconnect.config;

import com.bloodconnect.exception.DatabaseException;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production-ready Database connection manager using JDBC, Singleton pattern,
 * dynamic cloud environment variable resolution (Render, Docker, Kubernetes, Local),
 * and zero-leakage security masking.
 */
public class DatabaseConnection {
    private static final Logger LOGGER = Logger.getLogger(DatabaseConnection.class.getName());
    public static final String DEFAULT_DRIVER = "com.mysql.cj.jdbc.Driver";

    private static DatabaseConnection instance;

    private String driver = DEFAULT_DRIVER;
    private String url;
    private String user = "root";
    private String password = "";
    private boolean fallbackH2 = true;
    private boolean isH2Active = false;
    private boolean mysqlSchemaChecked = false;

    private DatabaseConnection() {
        loadConfiguration();
        initializeDriver();
    }

    public static synchronized DatabaseConnection getInstance() {
        if (instance == null) {
            instance = new DatabaseConnection();
        }
        return instance;
    }

    private void loadConfiguration() {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "application.properties could not be loaded, using defaults.", e);
        }

        // Optional local development override (application-local.properties is gitignored and dockerignored)
        try (InputStream localIn = getClass().getClassLoader().getResourceAsStream("application-local.properties")) {
            if (localIn != null) {
                props.load(localIn);
            }
        } catch (Exception ignored) {
            // Optional local file
        }

        // 1. Resolve JDBC Driver class (Never null)
        String envDriver = getEnv("DB_DRIVER", "MYSQL_DRIVER");
        if (envDriver != null && !envDriver.isEmpty()) {
            this.driver = envDriver;
        } else {
            String propDriver = props.getProperty("db.driver");
            this.driver = (propDriver != null && !propDriver.trim().isEmpty()) ? propDriver.trim() : DEFAULT_DRIVER;
        }

        // 2. Resolve Credentials (Environment variables take precedence over properties files)
        String envUser = getEnv("DB_USER", "MYSQL_USER", "MYSQLUSER");
        if (envUser != null && !envUser.isEmpty()) {
            this.user = envUser;
        } else {
            this.user = props.getProperty("db.user", "root");
        }

        String envPass = getEnv("DB_PASSWORD", "MYSQL_PASSWORD", "MYSQLPASSWORD");
        if (envPass != null) {
            this.password = envPass;
        } else {
            this.password = props.getProperty("db.password", "");
        }

        // 3. Fallback H2 Mode (Disable in production on Render with DB_FALLBACK_H2=false)
        String envFallback = getEnv("DB_FALLBACK_H2", "ENABLE_H2_FALLBACK");
        if (envFallback != null && !envFallback.isEmpty()) {
            this.fallbackH2 = Boolean.parseBoolean(envFallback);
        } else {
            this.fallbackH2 = Boolean.parseBoolean(props.getProperty("db.fallback.h2", "true"));
        }

        // 4. Resolve Database URL dynamically
        this.url = resolveDatabaseUrl(props);

        LOGGER.info(String.format("Database configuration armed: Target=%s, User=%s, Driver=%s, H2FallbackEnabled=%b",
                maskUrl(this.url), this.user, this.driver, this.fallbackH2));
    }

    /**
     * Resolves the primary database URL supporting:
     * 1. DB_URL (Standard JDBC format)
     * 2. DATABASE_URL / MYSQL_URL (Cloud service URI format, e.g. mysql://user:pass@host:port/db)
     * 3. DB_HOST, DB_PORT, DB_NAME (Individual parameters common in Render private networking)
     * 4. application.properties file
     * 5. Localhost fallback
     */
    private String resolveDatabaseUrl(Properties props) {
        // Priority 1: Direct JDBC URL via DB_URL or MYSQL_JDBC_URL
        String envDbUrl = getEnv("DB_URL", "MYSQL_JDBC_URL");
        if (envDbUrl != null && !envDbUrl.isEmpty()) {
            LOGGER.info("Using database connection from DB_URL environment variable.");
            return ensureJdbcPrefix(envDbUrl);
        }

        // Priority 2: Cloud URI via DATABASE_URL or MYSQL_URL
        String envRawUrl = getEnv("DATABASE_URL", "MYSQL_URL");
        if (envRawUrl != null && !envRawUrl.isEmpty()) {
            try {
                String parsed = parseCloudDatabaseUrl(envRawUrl);
                LOGGER.info("Using database connection from DATABASE_URL environment variable.");
                return parsed;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to parse DATABASE_URL (" + maskUrl(envRawUrl) + "): " + e.getMessage());
            }
        }

        // Priority 3: Individual host/port/name parameters (common in Render private services)
        String envHost = getEnv("DB_HOST", "MYSQL_HOST", "MYSQLHOST");
        if (envHost != null && !envHost.isEmpty()) {
            String port = getEnvOrDefault("3306", "DB_PORT", "MYSQL_PORT", "MYSQLPORT");
            String dbName = getEnvOrDefault("bloodconnect_db", "DB_NAME", "MYSQL_DATABASE", "MYSQLDATABASE");
            String sslParam = "false";
            String envSsl = getEnv("DB_SSL", "MYSQL_SSL");
            if ("true".equalsIgnoreCase(envSsl) || "require".equalsIgnoreCase(envSsl)) {
                sslParam = "true";
            }
            LOGGER.info(String.format("Constructed dynamic MySQL JDBC URL for host [%s:%s] database [%s]", envHost, port, dbName));
            return "jdbc:mysql://" + envHost + ":" + port + "/" + dbName
                    + "?useSSL=" + sslParam + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&autoReconnect=true";
        }

        // Priority 4: application.properties
        String propUrl = props.getProperty("db.url");
        if (propUrl != null && !propUrl.trim().isEmpty()) {
            LOGGER.info("Using database URL from application.properties file.");
            return propUrl.trim();
        }

        // Priority 5: Default local fallback
        LOGGER.info("No external DB environment variables detected. Falling back to default localhost:3306.");
        return "jdbc:mysql://localhost:3306/bloodconnect_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&autoReconnect=true";
    }

    private String parseCloudDatabaseUrl(String rawUrl) throws Exception {
        if (rawUrl.startsWith("jdbc:")) {
            return rawUrl;
        }

        String parseable = rawUrl;
        if (parseable.startsWith("mysql://")) {
            parseable = "http://" + parseable.substring("mysql://".length());
        } else if (parseable.startsWith("mysqls://")) {
            parseable = "https://" + parseable.substring("mysqls://".length());
        }

        URI uri = new URI(parseable);
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : 3306;
        String path = uri.getPath();
        String dbName = (path != null && path.length() > 1) ? path.substring(1) : "bloodconnect_db";

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            String[] parts = userInfo.split(":", 2);
            if (this.user == null || this.user.isEmpty() || "root".equals(this.user)) {
                this.user = parts[0];
            }
            if (parts.length > 1 && (this.password == null || this.password.isEmpty())) {
                this.password = parts[1];
            }
        }

        boolean useSsl = rawUrl.startsWith("mysqls://") || rawUrl.contains("ssl=true");
        return "jdbc:mysql://" + host + ":" + port + "/" + dbName
                + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&autoReconnect=true";
    }

    private String ensureJdbcPrefix(String rawUrl) {
        if (!rawUrl.startsWith("jdbc:")) {
            return "jdbc:" + rawUrl;
        }
        return rawUrl;
    }

    private String getEnv(String... keys) {
        for (String k : keys) {
            String val = System.getenv(k);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }
        return null;
    }

    private String getEnvOrDefault(String defaultValue, String... keys) {
        String val = getEnv(keys);
        return (val != null) ? val : defaultValue;
    }

    private String maskUrl(String rawUrl) {
        if (rawUrl == null) return "null";
        return rawUrl.replaceAll("(?i)(password=)[^&;]*", "$1*****")
                .replaceAll("(?i)(pwd=)[^&;]*", "$1*****")
                .replaceAll("(://[^:]+:)[^@]+(@)", "$1*****$2");
    }

    private void initializeDriver() {
        try {
            Class.forName(this.driver);
            LOGGER.info("Primary MySQL JDBC Driver registered successfully: " + this.driver);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "FATAL: Could not load primary JDBC Driver class: " + this.driver, e);
        }
    }

    /**
     * Obtains a JDBC Connection.
     * Tries configured primary database (MySQL) first.
     * If MySQL fails and fallbackH2 is enabled, connects to embedded H2 database.
     */
    public Connection getConnection() throws DatabaseException {
        try {
            Connection conn = DriverManager.getConnection(url, user, password);
            isH2Active = false;

            // Auto-provision tables if newly created empty database on Render
            if (!mysqlSchemaChecked) {
                checkAndInitializeMysqlSchema(conn);
                mysqlSchemaChecked = true;
            }

            return conn;
        } catch (SQLException primaryEx) {
            String errorMsg = String.format("Failed to connect to MySQL database at [%s] as user [%s]. Error [%d / %s]: %s",
                    maskUrl(url), user, primaryEx.getErrorCode(), primaryEx.getSQLState(), primaryEx.getMessage());

            if (fallbackH2) {
                LOGGER.warning(errorMsg + ". Activating H2 fallback mode for emergency continuity.");
                return getH2FallbackConnection();
            }

            LOGGER.severe(errorMsg);
            throw new DatabaseException(errorMsg, primaryEx);
        }
    }

    /**
     * Automatically creates MySQL tables if deployed to a fresh empty MySQL service on Render.
     */
    private void checkAndInitializeMysqlSchema(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "user_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "full_name VARCHAR(100) NOT NULL, "
                    + "email VARCHAR(150) NOT NULL UNIQUE, "
                    + "phone VARCHAR(20) NOT NULL, "
                    + "password_hash VARCHAR(255) NOT NULL, "
                    + "role VARCHAR(20) NOT NULL DEFAULT 'REQUESTER', "
                    + "city VARCHAR(100) NOT NULL, "
                    + "state VARCHAR(100) NOT NULL, "
                    + "pincode VARCHAR(20) NOT NULL, "
                    + "address TEXT, "
                    + "account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "INDEX idx_user_email (email), "
                    + "INDEX idx_user_city (city), "
                    + "INDEX idx_user_role (role)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            stmt.execute("CREATE TABLE IF NOT EXISTS donors ("
                    + "donor_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "user_id INT NOT NULL UNIQUE, "
                    + "blood_group VARCHAR(10) NOT NULL, "
                    + "availability_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', "
                    + "is_discoverable BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "CONSTRAINT fk_donor_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE, "
                    + "INDEX idx_donor_blood_group (blood_group), "
                    + "INDEX idx_donor_availability (availability_status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            stmt.execute("CREATE TABLE IF NOT EXISTS blood_requests ("
                    + "request_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "requester_id INT NOT NULL, "
                    + "patient_name VARCHAR(100) NOT NULL, "
                    + "blood_group VARCHAR(10) NOT NULL, "
                    + "units_required INT NOT NULL DEFAULT 1, "
                    + "hospital_name VARCHAR(150) NOT NULL, "
                    + "hospital_address TEXT NOT NULL, "
                    + "city VARCHAR(100) NOT NULL, "
                    + "state VARCHAR(100) NOT NULL, "
                    + "pincode VARCHAR(20) NOT NULL, "
                    + "contact_person VARCHAR(100) NOT NULL, "
                    + "contact_phone VARCHAR(20) NOT NULL, "
                    + "urgency VARCHAR(20) NOT NULL DEFAULT 'NORMAL', "
                    + "required_date DATE NOT NULL, "
                    + "description TEXT, "
                    + "request_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "CONSTRAINT fk_request_user FOREIGN KEY (requester_id) REFERENCES users(user_id) ON DELETE CASCADE, "
                    + "INDEX idx_request_blood_group (blood_group), "
                    + "INDEX idx_request_city (city), "
                    + "INDEX idx_request_pincode (pincode), "
                    + "INDEX idx_request_status (request_status), "
                    + "INDEX idx_request_urgency (urgency)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            stmt.execute("CREATE TABLE IF NOT EXISTS donor_responses ("
                    + "response_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "request_id INT NOT NULL, "
                    + "donor_id INT NOT NULL, "
                    + "response_status VARCHAR(20) NOT NULL, "
                    + "response_message TEXT, "
                    + "responded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT fk_response_request FOREIGN KEY (request_id) REFERENCES blood_requests(request_id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_response_donor FOREIGN KEY (donor_id) REFERENCES donors(donor_id) ON DELETE CASCADE, "
                    + "CONSTRAINT uq_request_donor UNIQUE (request_id, donor_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // Seed default administrator if not present
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users WHERE email='admin@bloodconnect.org'")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    stmt.execute("INSERT INTO users (full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                            + "VALUES ('System Administrator', 'admin@bloodconnect.org', '9876543210', "
                            + "'$2a$10$CdaRoius1I3wi862fgi/XOqkHXx28Gm2.4EbE1Le5UXTMtluPg8/q', 'ADMIN', 'Mumbai', 'Maharashtra', '400001', 'HQ Nariman Point', 'ACTIVE')");
                    LOGGER.info("Default administrator account initialized in MySQL.");
                }
            }

            // Seed demo donor if not present
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users WHERE email='rohit.sharma@example.com'")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    stmt.execute("INSERT INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                            + "VALUES (2, 'Rohit Sharma', 'rohit.sharma@example.com', '9820011223', "
                            + "'$2a$10$hIb1pOM1heRUNZvcGyujB.YcQjBJ9aQ66CL4ISWJcQt1DUk7CZ/pi', 'DONOR', 'Mumbai', 'Maharashtra', '400050', 'Bandra West', 'ACTIVE')");
                    stmt.execute("INSERT INTO donors (donor_id, user_id, blood_group, availability_status, is_discoverable) "
                            + "VALUES (1, 2, 'O+', 'AVAILABLE', TRUE)");
                    LOGGER.info("Demo donor account initialized in MySQL.");
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.INFO, "MySQL schema check complete: " + e.getMessage());
        }
    }

    private synchronized Connection getH2FallbackConnection() throws DatabaseException {
        try {
            Class.forName("org.h2.Driver");
            String h2Url = "jdbc:h2:mem:bloodconnect_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            Connection conn = DriverManager.getConnection(h2Url, "sa", "");
            if (!isH2Active) {
                initializeH2Schema(conn);
                isH2Active = true;
            }
            return conn;
        } catch (Exception e) {
            throw new DatabaseException("Failed to establish H2 fallback database connection", e);
        }
    }

    private void initializeH2Schema(Connection conn) {
        LOGGER.info("Initializing in-memory schema and seed data in H2 fallback...");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "user_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "full_name VARCHAR(100) NOT NULL, "
                    + "email VARCHAR(150) NOT NULL UNIQUE, "
                    + "phone VARCHAR(20) NOT NULL, "
                    + "password_hash VARCHAR(255) NOT NULL, "
                    + "role VARCHAR(20) NOT NULL DEFAULT 'REQUESTER', "
                    + "city VARCHAR(100) NOT NULL, "
                    + "state VARCHAR(100) NOT NULL, "
                    + "pincode VARCHAR(20) NOT NULL, "
                    + "address TEXT, "
                    + "account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            stmt.execute("CREATE TABLE IF NOT EXISTS donors ("
                    + "donor_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "user_id INT NOT NULL UNIQUE, "
                    + "blood_group VARCHAR(10) NOT NULL, "
                    + "availability_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', "
                    + "is_discoverable BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT fk_donor_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            stmt.execute("CREATE TABLE IF NOT EXISTS blood_requests ("
                    + "request_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "requester_id INT NOT NULL, "
                    + "patient_name VARCHAR(100) NOT NULL, "
                    + "blood_group VARCHAR(10) NOT NULL, "
                    + "units_required INT NOT NULL DEFAULT 1, "
                    + "hospital_name VARCHAR(150) NOT NULL, "
                    + "hospital_address TEXT NOT NULL, "
                    + "city VARCHAR(100) NOT NULL, "
                    + "state VARCHAR(100) NOT NULL, "
                    + "pincode VARCHAR(20) NOT NULL, "
                    + "contact_person VARCHAR(100) NOT NULL, "
                    + "contact_phone VARCHAR(20) NOT NULL, "
                    + "urgency VARCHAR(20) NOT NULL DEFAULT 'NORMAL', "
                    + "required_date DATE NOT NULL, "
                    + "description TEXT, "
                    + "request_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT fk_request_user FOREIGN KEY (requester_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            stmt.execute("CREATE TABLE IF NOT EXISTS donor_responses ("
                    + "response_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "request_id INT NOT NULL, "
                    + "donor_id INT NOT NULL, "
                    + "response_status VARCHAR(20) NOT NULL, "
                    + "response_message TEXT, "
                    + "responded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT fk_response_request FOREIGN KEY (request_id) REFERENCES blood_requests(request_id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_response_donor FOREIGN KEY (donor_id) REFERENCES donors(donor_id) ON DELETE CASCADE, "
                    + "CONSTRAINT uq_request_donor UNIQUE (request_id, donor_id))");

            // Seed Admin
            stmt.execute("MERGE INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                    + "KEY(email) VALUES (1, 'System Administrator', 'admin@bloodconnect.org', '9876543210', "
                    + "'$2a$10$CdaRoius1I3wi862fgi/XOqkHXx28Gm2.4EbE1Le5UXTMtluPg8/q', 'ADMIN', 'Mumbai', 'Maharashtra', '400001', 'HQ Nariman Point', 'ACTIVE')");

            // Seed Donors
            stmt.execute("MERGE INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                    + "KEY(email) VALUES (2, 'Rohit Sharma', 'rohit.sharma@example.com', '9820011223', '$2a$10$hIb1pOM1heRUNZvcGyujB.YcQjBJ9aQ66CL4ISWJcQt1DUk7CZ/pi', 'DONOR', 'Mumbai', 'Maharashtra', '400050', 'Bandra West', 'ACTIVE')");
            stmt.execute("MERGE INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                    + "KEY(email) VALUES (3, 'Priya Patel', 'priya.patel@example.com', '9820022334', '$2a$10$hIb1pOM1heRUNZvcGyujB.YcQjBJ9aQ66CL4ISWJcQt1DUk7CZ/pi', 'DONOR', 'Ahmedabad', 'Gujarat', '380015', 'Satellite Road', 'ACTIVE')");
            stmt.execute("MERGE INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                    + "KEY(email) VALUES (4, 'Arjun Verma', 'arjun.verma@example.com', '9820033445', '$2a$10$hIb1pOM1heRUNZvcGyujB.YcQjBJ9aQ66CL4ISWJcQt1DUk7CZ/pi', 'DONOR', 'Bengaluru', 'Karnataka', '560038', 'Indiranagar', 'ACTIVE')");
            stmt.execute("MERGE INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                    + "KEY(email) VALUES (5, 'Sneha Reddy', 'sneha.reddy@example.com', '9820044556', '$2a$10$hIb1pOM1heRUNZvcGyujB.YcQjBJ9aQ66CL4ISWJcQt1DUk7CZ/pi', 'DONOR', 'Hyderabad', 'Telangana', '500081', 'HITEC City', 'ACTIVE')");
            stmt.execute("MERGE INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                    + "KEY(email) VALUES (6, 'Vikram Singh', 'vikram.singh@example.com', '9820055667', '$2a$10$hIb1pOM1heRUNZvcGyujB.YcQjBJ9aQ66CL4ISWJcQt1DUk7CZ/pi', 'DONOR', 'Delhi', 'Delhi', '110001', 'CP', 'ACTIVE')");

            // Seed Requester
            stmt.execute("MERGE INTO users (user_id, full_name, email, phone, password_hash, role, city, state, pincode, address, account_status) "
                    + "KEY(email) VALUES (8, 'Rahul Mehta', 'rahul.mehta@example.com', '9811122233', '$2a$10$9rejbsoiMDS9DBmRuGLMVeGixYkreblz1ncLy.duAauodWZNH.nZm', 'REQUESTER', 'Pune', 'Maharashtra', '411004', 'Deccan', 'ACTIVE')");

            // Seed Donors mapping
            stmt.execute("MERGE INTO donors (donor_id, user_id, blood_group, availability_status, is_discoverable) KEY(user_id) VALUES (1, 2, 'O+', 'AVAILABLE', TRUE)");
            stmt.execute("MERGE INTO donors (donor_id, user_id, blood_group, availability_status, is_discoverable) KEY(user_id) VALUES (2, 3, 'A+', 'AVAILABLE', TRUE)");
            stmt.execute("MERGE INTO donors (donor_id, user_id, blood_group, availability_status, is_discoverable) KEY(user_id) VALUES (3, 4, 'B+', 'AVAILABLE', TRUE)");
            stmt.execute("MERGE INTO donors (donor_id, user_id, blood_group, availability_status, is_discoverable) KEY(user_id) VALUES (4, 5, 'AB+', 'AVAILABLE', TRUE)");
            stmt.execute("MERGE INTO donors (donor_id, user_id, blood_group, availability_status, is_discoverable) KEY(user_id) VALUES (5, 6, 'O-', 'AVAILABLE', TRUE)");

            // Seed Blood Requests
            stmt.execute("MERGE INTO blood_requests (request_id, requester_id, patient_name, blood_group, units_required, hospital_name, hospital_address, city, state, pincode, contact_person, contact_phone, urgency, required_date, description, request_status) "
                    + "KEY(request_id) VALUES (1, 8, 'Aarav Mehta', 'O+', 2, 'Lilavati Hospital', 'Bandra West', 'Mumbai', 'Maharashtra', '400050', 'Rahul Mehta', '9811122233', 'CRITICAL', CURRENT_DATE + 1, 'Urgent cardiac surgery', 'PENDING')");
            stmt.execute("MERGE INTO blood_requests (request_id, requester_id, patient_name, blood_group, units_required, hospital_name, hospital_address, city, state, pincode, contact_person, contact_phone, urgency, required_date, description, request_status) "
                    + "KEY(request_id) VALUES (2, 8, 'Devi Nair', 'B+', 1, 'Amrita Hospital', 'Edappally', 'Kochi', 'Kerala', '682041', 'Kavita Nair', '9811133344', 'URGENT', CURRENT_DATE + 2, 'Post accident trauma', 'ACCEPTED')");

            LOGGER.info("H2 fallback database successfully initialized.");
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Error initializing H2 fallback schema: " + e.getMessage(), e);
        }
    }

    /**
     * Utility method to safely close JDBC resources.
     */
    public static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable c : closeables) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean isH2Active() {
        return isH2Active;
    }
}
