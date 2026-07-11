package com.panopticon.config;

/**
 * Configuration for a single named datasource, bound from
 * {@code panopticon.datasources.<name>.*} in application.yml. Covers both
 * provider shapes (jdbc and jira) in one bean, same reasoning as
 * {@link com.panopticon.model.DataSourceDefinition}: two provider types
 * don't justify a polymorphic binding scheme yet.
 */
public class DatasourceDefinitionProperties {

    private String provider = "jdbc";

    // JDBC
    private String driverClassName;
    private String jdbcUrl;
    private String username;
    private String password = "";
    private String dialect = "generic";
    private boolean readOnly = false;
    private int maxPoolSize = 5;
    /** Optional classpath/file resource with DDL run once at startup (demo datasources only). */
    private String initSchema;
    /** Optional classpath/file resource with seed data run once at startup (demo datasources only). */
    private String initData;

    // Jira
    private String baseUrl;
    private JiraAuthProperties auth;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public String getInitSchema() {
        return initSchema;
    }

    public void setInitSchema(String initSchema) {
        this.initSchema = initSchema;
    }

    public String getInitData() {
        return initData;
    }

    public void setInitData(String initData) {
        this.initData = initData;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public JiraAuthProperties getAuth() {
        return auth;
    }

    public void setAuth(JiraAuthProperties auth) {
        this.auth = auth;
    }

    public static class JiraAuthProperties {
        private String type;
        private String token;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
