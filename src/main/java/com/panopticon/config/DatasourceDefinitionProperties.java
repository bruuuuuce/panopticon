package com.panopticon.config;

/**
 * Configuration for a single named datasource, bound from
 * {@code panopticon.datasources.<name>.*} in application.yml.
 */
public class DatasourceDefinitionProperties {

    private String driverClassName;
    private String url;
    private String username;
    private String password = "";
    private int maxPoolSize = 5;
    /** Optional classpath/file resource with DDL run once at startup (demo datasources only). */
    private String initSchema;
    /** Optional classpath/file resource with seed data run once at startup (demo datasources only). */
    private String initData;

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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
}
